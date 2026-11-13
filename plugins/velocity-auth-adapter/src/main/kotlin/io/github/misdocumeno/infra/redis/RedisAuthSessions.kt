package io.github.misdocumeno.infra.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.slf4j.Logger

/**
 * Source of truth helpers for `game-sessions:authenticated`.
 * Login still goes through the auth HTTP service; Velocity uses this for
 * crash/restart/reconcile paths where HTTP may be late or unavailable.
 */
class RedisAuthSessions(
    private val logger: Logger,
) : AutoCloseable {
    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    fun connect(redisUrl: String): Boolean {
        return try {
            val c = RedisClient.create(redisUrl)
            client = c
            connection = c.connect()
            true
        } catch (e: Exception) {
            logger.warn("auth-adapter redis unavailable: {}", e.message)
            false
        }
    }

    /** Drop every session (proxy boot / shutdown). */
    fun clearAll() {
        val sync = connection?.sync() ?: return
        try {
            val n = sync.del(AUTH_SET)
            logger.info("cleared {} auth session key(s) on {}", n, AUTH_SET)
        } catch (e: Exception) {
            logger.warn("clearAll auth sessions failed: {}", e.message)
        }
    }

    fun invalidate(username: String) {
        val sync = connection?.sync() ?: return
        try {
            sync.srem(AUTH_SET, username)
            sync.publish(EVENTS, """{"type":"Invalidated","username":${jsonString(username)}}""")
        } catch (e: Exception) {
            logger.warn("redis invalidate {} failed: {}", username, e.message)
        }
    }

    /** Remove Redis members who are not currently on this proxy. */
    fun reconcile(onlineUsernames: Set<String>): Int {
        val sync = connection?.sync() ?: return 0
        return try {
            val members = sync.smembers(AUTH_SET).orEmpty()
            var removed = 0
            for (name in members) {
                if (name !in onlineUsernames) {
                    sync.srem(AUTH_SET, name)
                    sync.publish(EVENTS, """{"type":"Invalidated","username":${jsonString(name)}}""")
                    removed++
                }
            }
            if (removed > 0) {
                logger.info("reconcile removed {} orphan auth session(s)", removed)
            }
            removed
        } catch (e: Exception) {
            logger.warn("auth session reconcile failed: {}", e.message)
            0
        }
    }

    override fun close() {
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        try {
            client?.shutdown()
        } catch (_: Exception) {
        }
        connection = null
        client = null
    }

    companion object {
        const val AUTH_SET = "game-sessions:authenticated"
        private const val EVENTS = "game-sessions:events"

        private fun jsonString(value: String): String {
            val escaped =
                value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
            return "\"$escaped\""
        }
    }
}
