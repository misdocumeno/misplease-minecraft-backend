package io.github.misdocumeno.infra.redis

import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class RedisTeleportListener(
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val adapter: RedisTeleportAdapter,
) {

    fun start() {
        connection.apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == "global_teleport:execute") {
                        adapter.onRawMessage(message)
                    }
                }
            })
            async().subscribe("global_teleport:execute")
        }
    }
}