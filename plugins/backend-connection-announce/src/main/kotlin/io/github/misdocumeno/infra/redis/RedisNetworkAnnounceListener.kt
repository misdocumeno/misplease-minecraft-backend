package io.github.misdocumeno.infra.redis

import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection

class RedisNetworkAnnounceListener(
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val adapter: RedisNetworkAnnounceAdapter,
) {
    fun start() {
        connection.apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == RedisNetworkAnnouncePublisher.CHANNEL) {
                        adapter.onRawMessage(message)
                    }
                }
            })
            async().subscribe(RedisNetworkAnnouncePublisher.CHANNEL)
        }
    }
}
