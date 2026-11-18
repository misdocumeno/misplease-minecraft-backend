package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.model.NetworkAnnounce
import io.github.misdocumeno.infra.redis.RedisNetworkAnnouncePublisher
import io.papermc.paper.advancement.AdvancementDisplay
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
        // Built from the display instead of event.message() on purpose: the message is
        // absent when gamerule announceAdvancements is off, and the translatable key
        // keeps the client's own language and the vanilla frame colours.
        val line = Component.translatable(
            advancementKey(display),
            Component.text(event.player.name),
            display.title(),
        )
        announces.publish(
            NetworkAnnounce.Advancement(
                username = event.player.name,
                miniMessage = mm.serialize(line.hoverEvent(Timestamps.hover())),
            ),
        )
    }

    private fun advancementKey(display: AdvancementDisplay): String =
        when (display.frame()) {
            AdvancementDisplay.Frame.CHALLENGE -> "chat.type.advancement.challenge"
            AdvancementDisplay.Frame.GOAL -> "chat.type.advancement.goal"
            else -> "chat.type.advancement.task"
        }

}
