package io.github.misdocumeno.infra.redis

import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class RedisAuthListener(
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val adapter: RedisAuthAdapter,
) {

    fun start() {
        connection.apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == "game-sessions:events") {
                        adapter.onRawMessage(message)
                    }
                }
            })
            async().subscribe("game-sessions:events")
        }
    }
}