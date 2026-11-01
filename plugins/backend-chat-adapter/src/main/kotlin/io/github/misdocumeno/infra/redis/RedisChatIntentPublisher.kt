package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.ChatIntentPublisher
import io.github.misdocumeno.domain.ChatIntent
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RedisChatIntentPublisher(
    private val connection: StatefulRedisConnection<String, String>,
    private val json: Json,
) : ChatIntentPublisher {

    override fun publish(intent: ChatIntent) {
        connection.async().publish("chat:intent", json.encodeToString(intent))
    }
}