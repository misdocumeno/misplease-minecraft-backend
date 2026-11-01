package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.ChatBroadcastHandler
import io.github.misdocumeno.domain.ChatBroadcast
import kotlinx.serialization.json.Json

class RedisChatBroadcastAdapter(
    private val handler: ChatBroadcastHandler,
    private val json: Json,
) {

    fun onRawMessage(message: String) {
        val broadcast = try {
            json.decodeFromString<ChatBroadcast>(message)
        } catch (e: Exception) {
            System.err.println("[BackendChatAdapter] Error decoding: $message")
            e.printStackTrace()
            return
        }

        handler.handle(broadcast)
    }
}