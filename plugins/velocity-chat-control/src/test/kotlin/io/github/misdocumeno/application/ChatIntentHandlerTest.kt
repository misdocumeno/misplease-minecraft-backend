package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthenticatedRepository
import io.github.misdocumeno.domain.ChatBroadcaster
import io.github.misdocumeno.domain.MuteLookup
import io.github.misdocumeno.domain.UsernameRepository
import io.github.misdocumeno.domain.model.ChatAudience
import io.github.misdocumeno.domain.model.ChatBroadcast
import io.github.misdocumeno.domain.model.ChatIntent
import io.github.misdocumeno.domain.model.MuteState
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ChatIntentHandlerTest {

    private val sender: Uuid = Uuid.random()
    private val broadcasts = mutableListOf<ChatBroadcast>()
    private val logs = mutableListOf<String>()

    private val broadcaster = object : ChatBroadcaster {
        override fun broadcast(broadcast: ChatBroadcast) {
            broadcasts += broadcast
        }
    }

    private val usernames = object : UsernameRepository {
        override fun getUsername(uuid: Uuid): String? = if (uuid == sender) "Steve" else null
    }

    private class Auth(private val authenticated: Boolean) : AuthenticatedRepository {
        override fun isAuthenticated(uuid: Uuid): Boolean = authenticated
    }

    private class Mutes(private val mute: MuteState?) : MuteLookup {
        override fun activeMute(playerId: Uuid): CompletableFuture<MuteState?> =
            CompletableFuture.completedFuture(mute)
    }

    private fun handler(mute: MuteState?, authenticated: Boolean = true) = ChatIntentHandler(
        authRepo = Auth(authenticated),
        audienceResolver = ChatAudienceResolver(),
        formatter = ChatFormatter(usernames),
        broadcaster = broadcaster,
        usernames = usernames,
        mutes = Mutes(mute),
        muteNotice = MuteNotice(),
        log = { logs += it },
    )

    private fun intent(message: String = "hola") = ChatIntent(
        messageId = Uuid.random(),
        senderUUID = sender,
        rawMessage = message,
        timestamp = 0,
    )

    @Test
    fun `authenticated and unmuted player is relayed to everyone and logged`() {
        handler(mute = null).handle(intent())

        assertEquals(1, broadcasts.size, "exactly one broadcast expected")
        assertEquals(ChatAudience.Global, broadcasts.single().audience)
        assertEquals("<Steve> hola", logs.single())
    }

    @Test
    fun `muted player gets the notice instead of a broadcast`() {
        handler(mute = MuteState(reason = "spam", endDate = null)).handle(intent("free admin pls"))

        val sent = broadcasts.single()
        assertEquals(ChatAudience.SpecificPlayers(setOf(sender)), sent.audience, "only the sender may see it")
        assertTrue(
            sent.rendered.miniMessage.contains("You are muted"),
            "the muted player must get feedback: ${sent.rendered.miniMessage}",
        )
        assertTrue(
            !sent.rendered.miniMessage.contains("free admin pls"),
            "the muted message must not be echoed anywhere",
        )
        assertTrue(logs.isEmpty(), "a dropped message must not be logged as chat")
    }

    @Test
    fun `unauthenticated player is dropped silently`() {
        handler(mute = null, authenticated = false).handle(intent())

        assertTrue(broadcasts.isEmpty(), "nothing may be broadcast before login")
        assertTrue(logs.isEmpty())
    }
}
