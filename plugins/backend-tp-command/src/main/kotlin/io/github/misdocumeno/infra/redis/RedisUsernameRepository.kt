package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.UsernameRepository
import io.lettuce.core.api.sync.RedisCommands
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicReference

/**
 * Tab-complete used to SMEMBERS on the main thread every keystroke. Keep a short-lived
 * cache refreshed on the async scheduler instead.
 */
class RedisUsernameRepository(
    private val redis: RedisCommands<String, String>,
    plugin: Plugin,
) : UsernameRepository {

    private val cached = AtomicReference<List<String>>(emptyList())

    init {
        refresh()
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable { refresh() }, 40L, 40L)
    }

    private fun refresh() {
        cached.set(redis.smembers(AUTH_SET).toList())
    }

    override fun getAllNames(): List<String> = cached.get()

    override fun findCaseInsensitive(username: String): String? {
        return getAllNames().find { it.equals(username, ignoreCase = true) }
    }

    override fun findPartial(partial: String): String? {
        return findPartialMultiple(partial).let { if (it.size == 1) it.first() else null }
    }

    override fun findPartialMultiple(partial: String): List<String> {
        return getAllNames().filter { it.contains(partial, ignoreCase = true) }
    }

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
    }
}
