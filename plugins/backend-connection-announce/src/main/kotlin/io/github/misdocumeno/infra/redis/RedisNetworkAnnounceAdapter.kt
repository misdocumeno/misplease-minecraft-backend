package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.NetworkAnnounceHandler
import io.github.misdocumeno.domain.model.NetworkAnnounce
import kotlinx.serialization.json.Json

class RedisNetworkAnnounceAdapter(
    private val handler: NetworkAnnounceHandler,
    private val json: Json,
) {
    fun onRawMessage(message: String) {
        val announce = try {
            json.decodeFromString<NetworkAnnounce>(message)
        } catch (e: Exception) {
            System.err.println("[ConnectionAnnounce] bad announce payload: $message")
            e.printStackTrace()
            return
        }
        handler.handle(announce)
    }
}
