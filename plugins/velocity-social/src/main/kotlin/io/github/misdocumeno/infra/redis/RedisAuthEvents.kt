package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.model.AuthEvent
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json

class RedisAuthEvents(
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val json: Json,
    private val onEvent: (AuthEvent) -> Unit,
) {
    fun start() {
        connection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                if (channel != CHANNEL) return
                runCatching { json.decodeFromString<AuthEvent>(message) }
                    .onSuccess(onEvent)
            }
        })
        connection.async().subscribe(CHANNEL)
    }

    companion object {
        private const val CHANNEL = "game-sessions:events"
    }
}
