package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.ChatAudience
import io.github.misdocumeno.domain.ChatBroadcast
import io.lettuce.core.api.StatefulRedisConnection
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import kotlin.uuid.toKotlinUuid

/**
 * Delivers global chat on the main thread.
 *
 * Redis pub/sub callbacks run on Netty I/O threads. Touching players or doing
 * per-player sync Redis there hitch the whole server (especially busy main).
 * One async SMEMBERS + a main-thread fan-out keeps chat off the tick.
 *
 * Chat sound lives here (not PlayMoreSounds): we cancel [AsyncChatEvent], so PMS
 * never sees the message. Same cue as PMS default: ENTITY_ITEM_PICKUP, everyone
 * except the sender.
 */
class PaperChatRenderer(
    private val plugin: Plugin,
    private val server: Server,
    private val connection: StatefulRedisConnection<String, String>,
) {

    private val mm = MiniMessage.miniMessage()

    fun render(broadcast: ChatBroadcast) {
        connection.async().smembers(AUTH_SET).whenComplete { authed, error ->
            if (error != null) {
                plugin.logger.warning("chat auth set failed: ${error.message}")
                return@whenComplete
            }
            val names = authed.orEmpty()
            Bukkit.getScheduler().runTask(plugin) { ->
                deliver(broadcast, names)
            }
        }
    }

    private fun deliver(broadcast: ChatBroadcast, authenticatedNames: Set<String>) {
        val component = mm.deserialize(broadcast.rendered.miniMessage)
        val senderId = broadcast.senderUUID

        for (player in server.onlinePlayers) {
            if (player.name !in authenticatedNames) continue
            if (!inAudience(player, broadcast.audience)) continue

            player.sendMessage(component)
            if (player.uniqueId.toKotlinUuid() != senderId) {
                player.playSound(player.location, CHAT_SOUND, CHAT_VOLUME, CHAT_PITCH)
            }
        }
    }

    private fun inAudience(player: Player, audience: ChatAudience): Boolean =
        when (audience) {
            ChatAudience.Global -> true
            is ChatAudience.SpecificPlayers -> player.uniqueId.toKotlinUuid() in audience.uuids
        }

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
        /** Match PlayMoreSounds default Player Chat sound on main. */
        private val CHAT_SOUND = Sound.ENTITY_ITEM_PICKUP
        private const val CHAT_VOLUME = 1.0f
        private const val CHAT_PITCH = 1.0f
    }
}
