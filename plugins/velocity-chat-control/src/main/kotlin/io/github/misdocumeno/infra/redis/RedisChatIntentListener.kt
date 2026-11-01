package io.github.misdocumeno.infra.redis

import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class RedisChatIntentListener(
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val adapter: RedisChatIntentAdapter,
) {

    fun start() {
        connection.apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == "chat:intent") {
                        adapter.onRawMessage(message)
                    }
                }
            })
            async().subscribe("chat:intent")
        }
    }
}