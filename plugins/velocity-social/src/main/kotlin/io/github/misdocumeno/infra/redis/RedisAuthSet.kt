package io.github.misdocumeno.infra.redis

import io.lettuce.core.api.StatefulRedisConnection

class RedisAuthSet(
    private val connection: StatefulRedisConnection<String, String>,
) {
    fun isAuthenticated(username: String): Boolean =
        connection.sync().sismember(AUTH_SET, username)

    fun authenticatedNames(): Set<String> =
        connection.sync().smembers(AUTH_SET).orEmpty()

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
    }
}
