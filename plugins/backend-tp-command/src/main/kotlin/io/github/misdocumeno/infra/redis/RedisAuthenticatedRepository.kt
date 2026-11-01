package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.AuthenticatedRepository
import io.lettuce.core.api.sync.RedisCommands

class RedisAuthenticatedRepository(
    private val redis: RedisCommands<String, String>,
) : AuthenticatedRepository {

    override fun isAuthenticated(username: String): Boolean {
        return redis.sismember("game-sessions:authenticated", username)
    }
}