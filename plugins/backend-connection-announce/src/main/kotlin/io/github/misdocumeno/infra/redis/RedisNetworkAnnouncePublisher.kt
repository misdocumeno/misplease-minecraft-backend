package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.model.NetworkAnnounce
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RedisNetworkAnnouncePublisher(
    private val connection: StatefulRedisConnection<String, String>,
    private val json: Json,
) {
    fun publish(announce: NetworkAnnounce) {
        connection.async().publish(CHANNEL, json.encodeToString(announce))
    }

    companion object {
        const val CHANNEL = "misplease:announce"
    }
}
