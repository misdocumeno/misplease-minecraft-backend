package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.TeleportAskHandler
import io.github.misdocumeno.domain.model.TeleportAskNotification
import kotlinx.serialization.json.Json

class RedisTeleportAskAdapter(
    private val handler: TeleportAskHandler,
    private val json: Json,
) {

    fun onRawMessage(message: String) {
        val notification = try {
            json.decodeFromString<TeleportAskNotification>(message)
        } catch (e: Exception) {
            System.err.println("[TeleportCommand] Error decoding: $message")
            e.printStackTrace()
            return
        }
        handler.handle(notification)
    }
}