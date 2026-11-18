package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.AuthQuery
import io.lettuce.core.api.StatefulRedisConnection

class RedisAuthQuery(
    private val connection: StatefulRedisConnection<String, String>,
) : AuthQuery {

    override suspend fun hasSession(username: String): Boolean {
        return try {
            connection.sync().sismember("game-sessions:authenticated", username)
        } catch (_: Exception) {
            // Fail closed: Redis blip / outage must not leave players "authed".
            false
        }
    }
}
