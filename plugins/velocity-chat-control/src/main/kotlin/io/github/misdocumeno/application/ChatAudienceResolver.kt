package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.ChatAudience
import io.github.misdocumeno.domain.model.ChatIntent

class ChatAudienceResolver {

    fun resolve(intent: ChatIntent): ChatAudience {
        // TODO: add filters (specific mutes, chat parties, idk, etc)
        return ChatAudience.Global
    }
}