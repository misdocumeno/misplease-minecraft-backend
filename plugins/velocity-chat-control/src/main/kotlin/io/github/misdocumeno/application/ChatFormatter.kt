package io.github.misdocumeno.application

import io.github.misdocumeno.domain.UsernameRepository
import io.github.misdocumeno.domain.model.ChatIntent
import io.github.misdocumeno.domain.model.RenderedChatMessage
import net.kyori.adventure.text.minimessage.MiniMessage

class ChatFormatter(
    private val usernames: UsernameRepository,
) {
    private val mm = MiniMessage.miniMessage()

    fun format(intent: ChatIntent): RenderedChatMessage {
        val name = intent.senderNameMiniMessage
            ?: "<gray>${usernames.getUsername(intent.senderUUID) ?: "<unknown>"}</gray>"
        return RenderedChatMessage(
            miniMessage = "<$name> ${mm.escapeTags(intent.rawMessage)}",
        )
    }
}
