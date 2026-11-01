package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.ChatBroadcaster
import io.github.misdocumeno.domain.model.ChatBroadcast
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.json.Json

class RedisChatBroadcaster(
    private val connection: StatefulRedisConnection<String, String>,
    private val json: Json,
) : ChatBroadcaster {

    override fun broadcast(broadcast: ChatBroadcast) {
        connection.async().publish("chat:broadcast", json.encodeToString(broadcast))
    }
}