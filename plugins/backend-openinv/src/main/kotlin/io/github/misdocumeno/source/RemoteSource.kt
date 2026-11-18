package io.github.misdocumeno.source

import io.github.misdocumeno.OpenInvPlugin
import io.github.misdocumeno.domain.InventoryKind
import io.github.misdocumeno.domain.ItemCodec
import io.github.misdocumeno.domain.Snapshot
import io.github.misdocumeno.domain.protocol.ApplyMessage
import io.github.misdocumeno.domain.protocol.RequestStateMessage
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Inventory on another backend. Reads/writes are Redis round trips and therefore
 * blocking: the session only touches this from the profile executor.
 *
 * The owner backend stays the single writer. It decides per request whether the
 * target is live there (per-slot deltas on the player) or offline (MVI profile),
 * so a target joining/leaving this backend never needs a second writer.
 */
class RemoteSource(
    private val plugin: OpenInvPlugin,
    private val sessionId: String,
    private val viewerName: String,
    private val viewerUuid: UUID,
    private val targetUuid: UUID,
    private val targetName: String,
    private val kind: InventoryKind,
    override val server: String,
    startGroup: String?,
) : InventorySource {

    override var group: String = startGroup.orEmpty()
        private set

    override val live: Boolean = false

    @Volatile
    var online: Boolean = false
        private set

    /** False when the owner runs a different game version: no writes are sent. */
    @Volatile
    var viewOnly: Boolean = false
        private set

    @Volatile
    var consecutiveFailures: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    override fun read(): Snapshot {
        val connection = plugin.transport
        check(connection.available) { "redis unavailable" }
        val state =
            connection.requestState(
                RequestStateMessage(
                    server = server,
                    sessionId = sessionId,
                    viewerName = viewerName,
                    viewerUuid = viewerUuid.toString(),
                    targetName = targetName,
                    targetUuid = targetUuid.toString(),
                    kind = kind.name,
                    group = group.ifBlank { null },
                ),
            ) ?: run {
                consecutiveFailures += 1
                error("no response from $server")
            }
        consecutiveFailures = 0
        if (!state.ok) {
            lastError = state.error
            error(state.error ?: "$server refused the request")
        }
        lastError = null
        if (state.group != null) group = state.group
        online = state.online
        viewOnly = state.mcVersion.isNotBlank() && state.mcVersion != ItemCodec.version()
        val items: List<ItemStack?> =
            (0 until kind.size).map { index ->
                runCatching { ItemCodec.decode(state.items.getOrNull(index)) }.getOrNull()
            }
        return Snapshot(kind, items, state.mcVersion)
    }

    override fun apply(changed: Map<Int, ItemStack?>) {
        if (viewOnly || changed.isEmpty()) return
        plugin.transport.sendApply(
            ApplyMessage(
                server = server,
                sessionId = sessionId,
                viewerName = viewerName,
                targetName = targetName,
                targetUuid = targetUuid.toString(),
                kind = kind.name,
                group = group,
                mcVersion = ItemCodec.version(),
                changed = changed.mapValues { (_, item) -> ItemCodec.encode(item) },
            ),
        )
    }
}
