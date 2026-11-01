package io.github.misdocumeno.infra.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class RedisChatBroadcastListener(
    private val redis: RedisClient,
    private val adapter: RedisChatBroadcastAdapter,
) {

    private lateinit var connection: StatefulRedisPubSubConnection<String, String>

    fun start() {
        connection = redis.connectPubSub().apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == "chat:broadcast") {
                        adapter.onRawMessage(message)
                    }
                }
            })
            async().subscribe("chat:broadcast")
        }
    }

    fun stop() {
        connection.close()
    }
}