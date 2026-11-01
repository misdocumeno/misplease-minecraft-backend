package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.AuthenticatedRepository
import io.lettuce.core.api.StatefulRedisConnection

class RedisAuthenticatedRepository(
    private val connection: StatefulRedisConnection<String, String>
) : AuthenticatedRepository {

    override fun isAuthenticated(username: String): Boolean {
        return connection.sync().sismember("game-sessions:authenticated", username)
    }
}