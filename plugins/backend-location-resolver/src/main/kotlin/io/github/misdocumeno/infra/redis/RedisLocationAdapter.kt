package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.LocationHandler
import io.github.misdocumeno.domain.model.LocationRequest
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.json.Json
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

class RedisLocationAdapter(
    private val plugin: Plugin,
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

        // Player location must be read on the main thread; reply stays async.
        Bukkit.getScheduler().runTask(plugin) { ->
            val response = handler.handle(request) ?: return@runTask
            connection.async().publish(
                "player:location:response:rpc",
                json.encodeToString(response),
            )
        }
    }
}
