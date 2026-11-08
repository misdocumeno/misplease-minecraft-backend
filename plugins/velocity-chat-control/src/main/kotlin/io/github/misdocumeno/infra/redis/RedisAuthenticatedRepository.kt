package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.AuthenticatedRepository
import io.github.misdocumeno.domain.UsernameRepository
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.uuid.Uuid

class RedisAuthenticatedRepository(
    private val connection: StatefulRedisConnection<String, String>,
    private val usernames: UsernameRepository,
) : AuthenticatedRepository {

    override fun isAuthenticated(uuid: Uuid): Boolean {
        val username = usernames.getUsername(uuid) ?: return false
        // Single check on the pub/sub worker; Velocity has no main tick to stall.
        // Prefer SISMEMBER over scanning the set for one username.
        return connection.sync().sismember("game-sessions:authenticated", username)
    }
}