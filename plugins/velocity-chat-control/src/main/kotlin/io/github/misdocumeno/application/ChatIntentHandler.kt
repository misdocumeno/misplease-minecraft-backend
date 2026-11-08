package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthenticatedRepository
import io.github.misdocumeno.domain.ChatBroadcaster
import io.github.misdocumeno.domain.model.ChatBroadcast
import io.github.misdocumeno.domain.model.ChatIntent

class ChatIntentHandler(
    private val authRepo: AuthenticatedRepository,
    private val audienceResolver: ChatAudienceResolver,
    private val formatter: ChatFormatter,
    private val broadcaster: ChatBroadcaster,
) {

    fun handle(intent: ChatIntent) {
        if (!authRepo.isAuthenticated(intent.senderUUID)) {
            return
        }

        val audience = audienceResolver.resolve(intent)
        val rendered = formatter.format(intent)

        broadcaster.broadcast(
            ChatBroadcast(intent.messageId, intent.senderUUID, audience, rendered)
        )
    }
}