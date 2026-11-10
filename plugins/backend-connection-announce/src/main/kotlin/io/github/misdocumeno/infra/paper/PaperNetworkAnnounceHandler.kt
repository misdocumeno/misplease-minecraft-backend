package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.NetworkAnnounceHandler
import io.github.misdocumeno.domain.model.NetworkAnnounce
import io.lettuce.core.api.StatefulRedisConnection
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.plugin.Plugin

/**
 * Fan-out death lines to authenticated players on this backend.
 * Origin server already suppressed the vanilla broadcast.
 */
class PaperNetworkAnnounceHandler(
    private val plugin: Plugin,
    private val server: Server,
    private val connection: StatefulRedisConnection<String, String>,
) : NetworkAnnounceHandler {
    private val mm = MiniMessage.miniMessage()

    override fun handle(announce: NetworkAnnounce) {
        val mini = when (announce) {
            is NetworkAnnounce.Death -> announce.miniMessage
        }
        connection.async().smembers(AUTH_SET).whenComplete { authed, error ->
            if (error != null) {
                plugin.logger.warning("announce auth set failed: ${error.message}")
                return@whenComplete
            }
            val names = authed.orEmpty()
            Bukkit.getScheduler().runTask(plugin) { ->
                val component = mm.deserialize(mini)
                for (player in server.onlinePlayers) {
                    if (player.name !in names) continue
                    player.sendMessage(component)
                }
            }
        }
    }

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
    }
}
