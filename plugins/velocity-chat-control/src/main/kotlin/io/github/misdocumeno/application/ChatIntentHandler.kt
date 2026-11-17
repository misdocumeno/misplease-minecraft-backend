package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthenticatedRepository
import io.github.misdocumeno.domain.ChatBroadcaster
import io.github.misdocumeno.domain.MuteLookup
import io.github.misdocumeno.domain.UsernameRepository
import io.github.misdocumeno.domain.model.ChatAudience
import io.github.misdocumeno.domain.model.ChatBroadcast
import io.github.misdocumeno.domain.model.ChatIntent

/**
 * Single choke point for global chat: authentication, mute gate, formatting, broadcast.
 *
 * The mute check is asynchronous (LibertyBans talks to PostgreSQL), so relays happen in
 * the lookup callback. While it is in flight the message is simply not broadcast: order
 * between players is not guaranteed by the existing pipeline either (Redis pub/sub).
 *
 * A muted player's message is dropped and never replayed; they get [MuteNotice] through
 * [ChatAudience.SpecificPlayers] instead.
 */
class ChatIntentHandler(
    private val authRepo: AuthenticatedRepository,
    private val audienceResolver: ChatAudienceResolver,
    private val formatter: ChatFormatter,
    private val broadcaster: ChatBroadcaster,
    private val usernames: UsernameRepository,
    private val mutes: MuteLookup,
    private val muteNotice: MuteNotice,
    private val log: (String) -> Unit = {},
) {

    fun handle(intent: ChatIntent) {
        if (!authRepo.isAuthenticated(intent.senderUUID)) {
            return
        }

        mutes.activeMute(intent.senderUUID).whenComplete { mute, _ ->
            if (mute != null) {
                val notice = muteNotice.render(mute)
                broadcaster.broadcast(
                    ChatBroadcast(
                        intent.messageId,
                        intent.senderUUID,
                        ChatAudience.SpecificPlayers(setOf(intent.senderUUID)),
                        notice,
                    ),
                )
                return@whenComplete
            }
            relay(intent)
        }
    }

    private fun relay(intent: ChatIntent) {
        val audience = audienceResolver.resolve(intent)
        val rendered = formatter.format(intent)

        log(ChatLogLine.of(nameOf(intent), intent.rawMessage))
        broadcaster.broadcast(
            ChatBroadcast(intent.messageId, intent.senderUUID, audience, rendered)
        )
    }

    private fun nameOf(intent: ChatIntent): String =
        usernames.getUsername(intent.senderUUID) ?: "unknown"
}
