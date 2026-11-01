package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.ChatIntentHandler
import io.github.misdocumeno.domain.model.ChatIntent
import kotlinx.serialization.json.Json

class RedisChatIntentAdapter(
    private val handler: ChatIntentHandler,
    private val json: Json,
) {

    fun onRawMessage(message: String) {
        val intent = try {
            json.decodeFromString<ChatIntent>(message)
        } catch (e: Exception) {
            System.err.println("[VelocityChatAdapter] Error decoding: $message")
            e.printStackTrace()
            return
        }

        handler.handle(intent)
    }
}