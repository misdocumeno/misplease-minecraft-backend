package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.infra.database.ExposedLastSessionRepository
import io.github.misdocumeno.infra.database.LastSessionPose
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

class LastSessionListener(
    private val plugin: JavaPlugin,
    private val repository: ExposedLastSessionRepository,
    private val serverId: String,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onJoin(event: PlayerJoinEvent) {
        persistAsync(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onQuit(event: PlayerQuitEvent) {
        persistAsync(event.player)
    }

    private fun persistAsync(player: Player) {
        val uuid = player.uniqueId
        val loc = player.location
        val pose =
            LastSessionPose(
                world = loc.world?.name ?: return,
                x = loc.x,
                y = loc.y,
                z = loc.z,
                yaw = loc.yaw,
                pitch = loc.pitch,
            )
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                repository.upsert(uuid, serverId, pose)
            } catch (e: Exception) {
                plugin.logger.warning("last-session upsert failed for $uuid: ${e.message}")
            }
        })
    }
}
