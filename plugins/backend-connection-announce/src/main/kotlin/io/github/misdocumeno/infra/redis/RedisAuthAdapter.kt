package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.AuthEventHandler
import io.github.misdocumeno.domain.model.AuthEvent
import kotlinx.serialization.json.Json

class RedisAuthAdapter(
    private val handler: AuthEventHandler,
    private val json: Json,
) {

    fun onRawMessage(message: String) {
        val event = try {
            json.decodeFromString<AuthEvent>(message)
        } catch (e: Exception) {
            System.err.println("[ConnectionAnnounce] Error decoding: $message")
            e.printStackTrace()
            return
        }
        handler.handle(event)
    }
}