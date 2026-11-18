package io.github.misdocumeno.owner

import io.github.misdocumeno.OpenInvPlugin
import io.github.misdocumeno.domain.InventoryKind
import io.github.misdocumeno.domain.ItemCodec
import io.github.misdocumeno.domain.protocol.AppliedMessage
import io.github.misdocumeno.domain.protocol.ApplyMessage
import io.github.misdocumeno.domain.protocol.RequestStateMessage
import io.github.misdocumeno.domain.protocol.StateMessage
import io.github.misdocumeno.source.LiveSource
import io.github.misdocumeno.source.ProfileSource
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Handles requests from viewers on other backends. This server owns the target,
 * so it is the only writer: per-slot changes go to the live player when they are
 * online here in the requested group, otherwise to the MVI group profile.
 *
 * Runs on the Redis pub/sub thread; every path hops to the main thread (live) or
 * the profile executor (MVI) before touching anything.
 */
class RemoteOwnerHandler(private val plugin: OpenInvPlugin) {

    fun onOpen(request: RequestStateMessage) {
        val uuid = runCatching { UUID.fromString(request.targetUuid) }.getOrNull()
            ?: return fail(request, "bad player id")
        val kind = InventoryKind.entries.firstOrNull { it.name == request.kind }
            ?: return fail(request, "bad inventory kind")

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val online = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline }
            val localGroup = online?.let { plugin.mvi.groupForWorld(it.world.name) }
            val liveEligible = online != null && (request.group == null || request.group == localGroup || localGroup == null)
            if (online != null && liveEligible) {
                val group = request.group ?: localGroup ?: plugin.mvi.defaultGroup() ?: "default"
                val snapshot = runCatching { LiveSource(online, kind, plugin.serverId, group).read() }
                    .getOrElse { return@Runnable fail(request, it.message ?: "read failed") }
                reply(request, group, online = true, snapshot.mcVersion, snapshot.items.map { ItemCodec.encode(it) })
                return@Runnable
            }

            plugin.profileExecutor.execute {
                val result = runCatching {
                    if (!plugin.mvi.available) error("Multiverse-Inventories is not available on ${plugin.serverId}")
                    val group = request.group
                        ?: plugin.mvi.lastWorld(uuid, request.targetName)?.let { plugin.mvi.groupForWorld(it) }
                        ?: plugin.mvi.defaultGroup()
                        ?: error("no inventory group")
                    val snapshot = plugin.mvi.read(uuid, request.targetName, group, kind)
                    group to snapshot
                }
                plugin.onMain {
                    result.onSuccess { (group, snapshot) ->
                        reply(request, group, online = false, snapshot.mcVersion, snapshot.items.map { ItemCodec.encode(it) })
                    }.onFailure { fail(request, it.message ?: "read failed") }
                }
            }
        })
    }

    fun onApply(message: ApplyMessage) {
        val uuid = runCatching { UUID.fromString(message.targetUuid) }.getOrNull()
            ?: return reject(message, "bad player id")
        val kind = InventoryKind.entries.firstOrNull { it.name == message.kind }
            ?: return reject(message, "bad inventory kind")
        if (message.mcVersion != ItemCodec.version()) {
            return reject(message, "game version mismatch")
        }
        val changed =
            runCatching {
                message.changed.mapValues { (_, encoded) -> ItemCodec.decode(encoded) }
            }.getOrElse { return reject(message, "undecodable item") }
        if (changed.isEmpty()) return

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val online = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline }
            val localGroup = online?.let { plugin.mvi.groupForWorld(it.world.name) }
            val liveEligible = online != null && (localGroup == null || localGroup == message.group)
            if (online != null && liveEligible) {
                runCatching { LiveSource(online, kind, plugin.serverId, message.group).apply(changed) }
                    .onSuccess { plugin.transport.replyApplied(AppliedMessage(plugin.serverId, message.sessionId, true)) }
                    .onFailure { reject(message, it.message ?: "write failed") }
                return@Runnable
            }
            plugin.profileExecutor.execute {
                val result = runCatching {
                    if (!plugin.mvi.available) error("Multiverse-Inventories is not available on ${plugin.serverId}")
                    ProfileSource(plugin.mvi, uuid, message.targetName, kind, plugin.serverId, message.group)
                        .apply(changed)
                }
                result.onSuccess {
                    plugin.transport.replyApplied(AppliedMessage(plugin.serverId, message.sessionId, true))
                }.onFailure { error ->
                    plugin.logger.warning("remote apply failed for ${message.targetName}: ${error.message}")
                    reject(message, error.message ?: "write failed")
                }
            }
        })
    }

    private fun reply(
        request: RequestStateMessage,
        group: String,
        online: Boolean,
        mcVersion: String,
        items: List<String?>,
    ) {
        plugin.transport.replyState(
            StateMessage(
                server = plugin.serverId,
                sessionId = request.sessionId,
                ok = true,
                group = group,
                online = online,
                mcVersion = mcVersion,
                items = items,
            ),
        )
    }

    private fun fail(request: RequestStateMessage, reason: String) {
        plugin.transport.replyState(
            StateMessage(
                server = plugin.serverId,
                sessionId = request.sessionId,
                ok = false,
                error = reason,
            ),
        )
    }

    private fun reject(message: ApplyMessage, reason: String) {
        plugin.logger.fine("remote apply rejected (${message.targetName}): $reason")
        plugin.transport.replyApplied(AppliedMessage(plugin.serverId, message.sessionId, false, reason))
    }
}
