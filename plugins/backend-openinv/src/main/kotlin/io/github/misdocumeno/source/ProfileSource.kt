package io.github.misdocumeno.source

import io.github.misdocumeno.domain.InventoryKind
import io.github.misdocumeno.domain.Snapshot
import io.github.misdocumeno.infra.mvi.MviBridge
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Multiverse-Inventories group profile. Reads and writes are blocking MVI calls,
 * so they are always invoked from the plugin's profile executor, never from the
 * server thread.
 */
class ProfileSource(
    private val bridge: MviBridge,
    private val uuid: UUID,
    private val name: String,
    private val kind: InventoryKind,
    override val server: String,
    override val group: String,
    /** Snapshot read from the world profile when the group cache did not exist yet. */
    private val seed: Snapshot? = null,
) : InventorySource {

    override val live: Boolean = false

    override fun read(): Snapshot = bridge.read(uuid, name, group, kind)

    override fun apply(changed: Map<Int, ItemStack?>) = bridge.apply(uuid, name, group, kind, changed, seed)
}
