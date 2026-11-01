package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.ChatIntent
import io.github.misdocumeno.domain.model.RenderedChatMessage
import io.github.misdocumeno.domain.UsernameRepository
import net.kyori.adventure.text.minimessage.MiniMessage

class ChatFormatter(
    private val usernames: UsernameRepository
) {

    private val mm = MiniMessage.miniMessage()

    fun format(intent: ChatIntent): RenderedChatMessage {
        val color = "gray" // TODO: get from permissions or something
        val username = usernames.getUsername(intent.senderUUID) ?: "<unknown>"
        return RenderedChatMessage(
            miniMessage = "<<$color>$username</$color>> ${mm.escapeTags(intent.rawMessage)}"
        )
    }
}