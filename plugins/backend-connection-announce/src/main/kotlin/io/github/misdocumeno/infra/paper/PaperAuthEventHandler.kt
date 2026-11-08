package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.AuthEventHandler
import io.github.misdocumeno.domain.model.AuthEvent
import io.lettuce.core.api.StatefulRedisConnection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.plugin.Plugin

class PaperAuthEventHandler(
    private val plugin: Plugin,
    private val server: Server,
    private val connection: StatefulRedisConnection<String, String>,
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
        val component = Component.translatable(key, NamedTextColor.YELLOW, Component.text(event.username))

        for (player in server.onlinePlayers) {
            if (player.name in authenticatedNames) {
                player.sendMessage(component)
            }
        }
    }

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
    }
}
