package io.github.misdocumeno.infra.redis

import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class RedisLocationListener(
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val adapter: RedisLocationAdapter,
) {

    fun start() {
        connection.apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == "player:location:request:rpc") {
                        adapter.onRawMessage(message)
                    }
                }
            })
            async().subscribe("player:location:request:rpc")
        }
    }
}