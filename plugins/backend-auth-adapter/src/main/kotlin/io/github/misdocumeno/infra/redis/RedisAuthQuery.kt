package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.AuthQuery
import redis.clients.jedis.JedisPool

class RedisAuthQuery(private val pool: JedisPool) : AuthQuery {

    override suspend fun hasSession(username: String): Boolean {
        return pool.resource.use { jedis ->
            jedis.sismember("game-sessions:authenticated", username)
        }
    }
}