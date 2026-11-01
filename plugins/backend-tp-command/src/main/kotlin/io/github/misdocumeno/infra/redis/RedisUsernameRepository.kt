package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.UsernameRepository
import io.lettuce.core.api.sync.RedisCommands

class RedisUsernameRepository(
    private val redis: RedisCommands<String, String>,
) : UsernameRepository {

    override fun getAllNames(): List<String> {
        return redis.smembers("game-sessions:authenticated").toList()
    }

    override fun findCaseInsensitive(username: String): String? {
        return getAllNames().find { it == username.lowercase() }
    }

    override fun findPartial(partial: String): String? {
        return findPartialMultiple(partial).let { if (it.size == 1) it.first() else null }
    }

    override fun findPartialMultiple(partial: String): List<String> {
        return getAllNames().filter { it.contains(partial, ignoreCase = true) }
    }
}