package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.LocationHandler
import io.github.misdocumeno.domain.model.LocationRequest
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.json.Json

/**
 * Location is read-only entity state. On Paper (non-Folia) that is acceptable off the
 * main thread; hopping to main would only add scheduling latency to every /tpahere RPC.
 * Publish stays async so we never block the Redis pub/sub worker on the network round-trip.
 */
class RedisLocationAdapter(
    private val handler: LocationHandler,
    private val connection: StatefulRedisConnection<String, String>,
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

        val response = handler.handle(request) ?: return
        connection.async().publish(
            "player:location:response:rpc",
            json.encodeToString(response),
        )
    }
}
