package io.github.misdocumeno.infra.redis

import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class RedisTeleportAskListener(
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val adapter: RedisTeleportAskAdapter,
) {

    fun start() {
        connection.apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == "tpa:request") {
                        adapter.onRawMessage(message)
                    }
                }
            })
            async().subscribe("tpa:request")
        }
    }
}