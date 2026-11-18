package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.model.NetworkAnnounce
import io.github.misdocumeno.infra.redis.RedisNetworkAnnouncePublisher
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Suppress local join/quit/death/advancement broadcasts and republish the ones
 * that belong to the network. Join/quit stay on auth Authenticated/Invalidated
 * (post-/login only).
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
                // Hover is attached before serializing so it travels with the line.
                miniMessage = mm.serialize(message.hoverEvent(Timestamps.hover())),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val display = event.advancement.display ?: return
        if (display.isHidden) return
        // Suppress the local line; the network gets our version below.
        event.message(null)
        // Same shape as vanilla: the translatable carries the plain sentence and
        // `displayName()` is the bracketed advancement name, coloured by the frame
        // (green task/goal, dark purple challenge) with the description tooltip.
        // `title()` alone dropped both the colour and the brackets.
        val line = Component.translatable(
            "chat.type.advancement.${display.frame().name.lowercase()}",
            event.player.displayName(),
            display.displayName(),
        )
        announces.publish(
            NetworkAnnounce.Advancement(
                username = event.player.name,
                miniMessage = mm.serialize(line.hoverEvent(Timestamps.hover())),
            ),
        )
    }

}
