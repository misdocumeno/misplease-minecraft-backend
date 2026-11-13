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
        // Literal <> brackets (gray). Only `<` needs MiniMessage escaping; `\>`
        // would show a visible backslash. Name keeps LP color; message is white.
        return RenderedChatMessage(
            miniMessage =
                "<gray>\\<</gray>$name<gray>></gray> <white>${mm.escapeTags(intent.rawMessage)}</white>",
        )
    }
}
