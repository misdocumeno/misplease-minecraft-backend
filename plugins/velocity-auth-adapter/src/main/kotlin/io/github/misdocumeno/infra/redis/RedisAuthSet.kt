package io.github.misdocumeno.infra.redis

import io.lettuce.core.api.StatefulRedisConnection

class RedisAuthSet(
    private val connection: StatefulRedisConnection<String, String>,
) {
    fun isAuthenticated(username: String): Boolean =
        runCatching { connection.sync().sismember(AUTH_SET, username) }.getOrDefault(false)

    /**
     * Seconds since `/login` created this session, or `null` when the key is
     * missing (older session, Redis blip). Used only by the reconciler so it can
     * leave a fresh session alone while the player is still in the limbo.
     */
    fun sessionAgeSeconds(username: String): Long? = runCatching {
        connection.sync().get("$CREATED_PREFIX$username")?.toLongOrNull()?.let { created ->
            (System.currentTimeMillis() / 1000) - created
        }
    }.getOrNull()

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
        private const val CREATED_PREFIX = "game-sessions:created-at:"
    }
}
