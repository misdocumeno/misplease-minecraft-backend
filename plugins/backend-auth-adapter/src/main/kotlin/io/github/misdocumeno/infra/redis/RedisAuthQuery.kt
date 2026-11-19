package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.AuthQuery
import io.lettuce.core.api.StatefulRedisConnection

class RedisAuthQuery(
    private val connection: StatefulRedisConnection<String, String>,
) : AuthQuery {

    override suspend fun hasSession(username: String): Boolean {
        return try {
            connection.sync().sismember(AUTH_SET, username)
        } catch (_: Exception) {
            // Fail closed: Redis blip / outage must not leave players "authed".
            false
        }
    }

    override suspend fun sessionLoginId(username: String): String? {
        return try {
            connection.sync().get("$ORIGIN_PREFIX$username")
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val AUTH_SET = "game-sessions:authenticated"

        /** Written by the auth service when `/login` succeeds; same id the proxy logs. */
        const val ORIGIN_PREFIX = "game-sessions:origin:"
    }
}
