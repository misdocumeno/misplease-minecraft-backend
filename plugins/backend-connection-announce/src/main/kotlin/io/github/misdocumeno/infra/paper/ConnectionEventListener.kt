package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.model.NetworkAnnounce
import io.github.misdocumeno.infra.redis.RedisNetworkAnnouncePublisher
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Suppress local join/quit/death broadcasts. Deaths are republished network-wide.
 * Join/quit stay on auth Authenticated/Invalidated (post-/login only).
 *
 * Advancements stay local / Multiverse-Inventories-scoped (not Redis-global).
 */
class ConnectionEventListener(
    private val announces: RedisNetworkAnnouncePublisher,
) : Listener {
    private val mm = MiniMessage.miniMessage()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoinClearEarly(event: PlayerJoinEvent) {
        event.joinMessage(null)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoinClearLate(event: PlayerJoinEvent) {
        event.joinMessage(null)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuitClearEarly(event: PlayerQuitEvent) {
        event.quitMessage(null)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuitClearLate(event: PlayerQuitEvent) {
        event.quitMessage(null)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val message = event.deathMessage() ?: return
        event.deathMessage(null)
        announces.publish(
            NetworkAnnounce.Death(
                username = event.player.name,
                miniMessage = mm.serialize(message),
            ),
        )
    }

}
