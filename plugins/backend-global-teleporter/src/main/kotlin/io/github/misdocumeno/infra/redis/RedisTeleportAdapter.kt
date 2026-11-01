package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.TeleportHandler
import io.github.misdocumeno.domain.model.TeleportRequest
import kotlinx.serialization.json.Json

class RedisTeleportAdapter(
    private val handler: TeleportHandler,
    private val json: Json,
) {

    fun onRawMessage(message: String) {
        val teleport = try {
            json.decodeFromString<TeleportRequest>(message)
        } catch (e: Exception) {
            System.err.println("[GlobalTeleporter] Error decoding: $message")
            e.printStackTrace()
            return
        }
        handler.handle(teleport)
    }
}