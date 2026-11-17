package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.AuthEventHandler
import io.github.misdocumeno.domain.model.AuthEvent
import io.lettuce.core.api.StatefulRedisConnection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.plugin.Plugin

/**
 * Global join/leave text+sound. Local joinMessage/quitMessage are cleared so vanilla
 * (and typically PlayMoreSounds join/leave tied to those cues) do not cover cross-server
 * presence — recipients hear the same notes PMS uses on main.
 */
class PaperAuthEventHandler(
    private val plugin: Plugin,
    private val server: Server,
    private val connection: StatefulRedisConnection<String, String>,
    private val sounds: AnnounceSounds,
) : AuthEventHandler {

    override fun handle(event: AuthEvent) {
        connection.async().smembers(AUTH_SET).whenComplete { authed, error ->
            if (error != null) {
                plugin.logger.warning("auth announce set failed: ${error.message}")
                return@whenComplete
            }
            val names = authed.orEmpty()
            Bukkit.getScheduler().runTask(plugin) { ->
                deliver(event, names)
            }
        }
    }

    private fun deliver(event: AuthEvent, authenticatedNames: Set<String>) {
        val key = when (event) {
            is AuthEvent.Authenticated -> "multiplayer.player.joined"
            is AuthEvent.Invalidated -> "multiplayer.player.left"
        }
        val sound = when (event) {
            is AuthEvent.Authenticated -> sounds.join
            is AuthEvent.Invalidated -> sounds.leave
        }
        val component = Component.translatable(key, NamedTextColor.YELLOW, Component.text(event.username))
            // Same tooltip the chat lines carry; the hover is built once and shared.
            .hoverEvent(Timestamps.hover())

        for (player in server.onlinePlayers) {
            if (player.name !in authenticatedNames) continue
            // Joiner already gets local feedback; leave target is usually gone.
            if (player.name.equals(event.username, ignoreCase = true)) continue
            player.sendMessage(component)
            player.playSound(player.location, sound, sounds.volume, sounds.pitch)
        }
    }

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
    }
}
