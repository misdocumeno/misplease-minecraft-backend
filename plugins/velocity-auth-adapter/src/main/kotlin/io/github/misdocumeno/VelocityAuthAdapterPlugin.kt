package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.application.SessionInvalidator
import io.github.misdocumeno.application.UsernameCaseChecker
import io.github.misdocumeno.domain.AuthApi
import io.github.misdocumeno.domain.UsernameCaseCheckResult
import io.github.misdocumeno.infra.http.KtorAuthApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.util.concurrent.TimeUnit

/**
 * Proxy is the truth for "is this player connected to the network".
 *
 * Velocity events:
 * - LoginEvent / DisconnectEvent: real proxy join/leave (NOT fired when moving main↔tech)
 * - ServerConnected / KickedFromServer: backend switches only — do NOT touch auth sessions
 *
 * Redis AUTH_SET is owned by the auth service. This plugin only calls auth HTTP:
 * - LoginEvent: invalidate (drop any stale session before backends check Redis)
 * - DisconnectEvent: invalidate (quit / timeout / proxy kick — every leave path)
 * - Proxy boot/shutdown: clear-all
 * - Reconcile: list authenticated, invalidate anyone not on the proxy
 *
 * Backends are the ones that *read* Redis on join to decide /login.
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
    private lateinit var api: AuthApi

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }

        api = KtorAuthApi(client)
        caseChecker = UsernameCaseChecker(api)
        invalidator = SessionInvalidator(api)

        runBlocking {
            try {
                api.clearAllSessions()
                logger.info("cleared auth sessions on proxy start")
            } catch (e: Exception) {
                logger.warn("clear-all on proxy start failed: {}", e.message)
            }
        }

        proxy.scheduler
            .buildTask(this, java.lang.Runnable { reconcileOrphans() })
            .repeat(15, TimeUnit.SECONDS)
            .schedule()
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
    fun onLogin(event: LoginEvent) {
        // Proxy join (not a backend switch). Belts-and-suspenders against stale Redis
        // after a crash where DisconnectEvent never ran.
        dropSession(event.player.username, "proxy-login")
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        dropSession(event.player.username, "proxy-disconnect")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        runBlocking {
            try {
                api.clearAllSessions()
            } catch (e: Exception) {
                logger.warn("clear-all on proxy shutdown failed: {}", e.message)
            }
        }
    }

    private fun dropSession(username: String, reason: String) {
        runBlocking {
            try {
                invalidator.invalidate(username)
            } catch (e: Exception) {
                logger.warn("invalidate {} ({}): {}", username, reason, e.message)
            }
        }
    }

    private fun reconcileOrphans() {
        runBlocking {
            try {
                val authed = api.listAuthenticated()
                val online = proxy.allPlayers.mapTo(HashSet()) { it.username }
                for (name in authed) {
                    if (name !in online) {
                        invalidator.invalidate(name)
                        logger.info("reconcile invalidated orphan session {}", name)
                    }
                }
            } catch (e: Exception) {
                logger.warn("auth session reconcile failed: {}", e.message)
            }
        }
    }
}
