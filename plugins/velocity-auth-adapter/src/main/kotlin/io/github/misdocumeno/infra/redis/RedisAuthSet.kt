package io.github.misdocumeno.infra.redis

import io.lettuce.core.api.StatefulRedisConnection

class RedisAuthSet(
    private val connection: StatefulRedisConnection<String, String>,
) {
    fun isAuthenticated(username: String): Boolean =
        runCatching { connection.sync().sismember(AUTH_SET, username) }.getOrDefault(false)

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
    }
}
