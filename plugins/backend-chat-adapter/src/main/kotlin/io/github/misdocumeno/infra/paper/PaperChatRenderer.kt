package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.domain.AuthenticatedRepository
import io.github.misdocumeno.domain.ChatAudience
import io.github.misdocumeno.domain.ChatBroadcast
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Server
import org.bukkit.entity.Player
import kotlin.uuid.toKotlinUuid

class PaperChatRenderer(private val server: Server, private val authRepo: AuthenticatedRepository) {

    fun render(broadcast: ChatBroadcast) {
        val component = MiniMessage.miniMessage().deserialize(broadcast.rendered.miniMessage)

        val authedAudience = server.filterAudience { p ->
            (p as? Player)?.uniqueId?.toKotlinUuid()?.let { authRepo.isAuthenticated(it) } ?: false
        }

        when (val audience = broadcast.audience) {
            ChatAudience.Global -> authedAudience.sendMessage(component)

            is ChatAudience.SpecificPlayers -> {
                authedAudience.filterAudience {
                    (it as? Player)?.uniqueId?.toKotlinUuid() in audience.uuids
                }.sendMessage(component)
            }
        }
    }
}