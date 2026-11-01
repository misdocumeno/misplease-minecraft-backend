package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.LocationHandler
import io.github.misdocumeno.domain.model.LocationRequest
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.Json

class RedisLocationAdapter(
    private val handler: LocationHandler,
    private val redis: RedisCommands<String, String>,
    private val json: Json,
) {

    fun onRawMessage(message: String) {
        val request = try {
            json.decodeFromString<LocationRequest>(message)
        } catch (e: Exception) {
            System.err.println("[LocationResolver] Error decoding: $message")
            e.printStackTrace()
            return
        }
        // if null, the player is not in this server
        val response = handler.handle(request) ?: return
        redis.publish("player:location:response:rpc", json.encodeToString(response))
    }
}