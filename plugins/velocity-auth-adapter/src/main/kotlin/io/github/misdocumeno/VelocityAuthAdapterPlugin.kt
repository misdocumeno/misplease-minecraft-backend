package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.application.SessionInvalidator
import io.github.misdocumeno.application.UsernameCaseChecker
import io.github.misdocumeno.domain.UsernameCaseCheckResult
import io.github.misdocumeno.infra.http.KtorAuthApi
import io.github.misdocumeno.infra.redis.RedisAuthSessions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.util.concurrent.TimeUnit

/**
 * Session lifecycle (must never leave a Redis auth membership without a live proxy player):
 *
 * - login (backend → auth HTTP) adds to Redis
 * - DisconnectEvent (quit / timeout / kick from proxy): invalidate
 * - KickedFromServerEvent (backend crash / shutdown / force kick): invalidate
 *   even if Velocity keeps the player and redirects — they must /login again
 * - ProxyInitialize: clear entire AUTH_SET (stale after proxy crash/restart)
 * - ProxyShutdown: clear entire AUTH_SET
 * - periodic reconcile: AUTH_SET ⊆ currently connected usernames
 * - HTTP invalidate first; Redis SREM as fallback if auth is down
 */
@Plugin(
    id = "auth-adapter",
    name = "AuthAdapter",
    version = "0.1.0",
    description = "External auth service adapter",
    authors = ["Mis"],
)
class VelocityAuthAdapterPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {

    private lateinit var caseChecker: UsernameCaseChecker
    private lateinit var invalidator: SessionInvalidator
    private lateinit var redisSessions: RedisAuthSessions

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }

        val api = KtorAuthApi(client)
        caseChecker = UsernameCaseChecker(api)
        invalidator = SessionInvalidator(api)
        redisSessions = RedisAuthSessions(logger)

        val redisUrl = System.getenv("REDIS_URL")?.trim().orEmpty()
        if (redisUrl.isNotEmpty() && redisSessions.connect(redisUrl)) {
            // Proxy (re)start: nothing on this process is logged in yet.
            redisSessions.clearAll()
            proxy.scheduler
                .buildTask(this, Runnable { reconcileOrphans() })
                .repeat(15, TimeUnit.SECONDS)
                .schedule()
        } else {
            logger.warn("REDIS_URL missing/unusable — orphan session reconcile disabled")
        }
    }

    @Subscribe
    fun onPreLogin(event: PreLoginEvent) = runBlocking {
        when (val result = caseChecker.check(event.username)) {
            UsernameCaseCheckResult.ExactMatch -> Unit

            UsernameCaseCheckResult.NotFound ->
                event.result = PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Register on misplease.com.ar")
                )

            is UsernameCaseCheckResult.WrongCase ->
                event.result = PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Please login with ${result.correctUsername}")
                )
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        dropSession(event.player.username, "proxy-disconnect")
    }

    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        // Backend crash/shutdown/kick: player may stay on the proxy (redirect).
        // Still drop Redis so a reconnect or next backend join requires /login.
        dropSession(event.player.username, "kicked-from-${event.server.serverInfo.name}")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        if (::redisSessions.isInitialized) {
            redisSessions.clearAll()
            redisSessions.close()
        }
    }

    private fun dropSession(username: String, reason: String) {
        runBlocking {
            var httpOk = false
            try {
                invalidator.invalidate(username)
                httpOk = true
            } catch (e: Exception) {
                logger.warn("HTTP invalidate {} ({}): {}", username, reason, e.message)
            }
            // Always also SREM locally: covers auth downtime and races.
            if (::redisSessions.isInitialized) {
                redisSessions.invalidate(username)
            }
            if (!httpOk) {
                logger.info("dropped session for {} via redis fallback ({})", username, reason)
            }
        }
    }

    private fun reconcileOrphans() {
        if (!::redisSessions.isInitialized) return
        val online = proxy.allPlayers.mapTo(HashSet()) { it.username }
        redisSessions.reconcile(online)
    }
}
