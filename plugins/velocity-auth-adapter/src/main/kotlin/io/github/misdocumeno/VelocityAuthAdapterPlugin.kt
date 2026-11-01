package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.application.SessionInvalidator
import io.github.misdocumeno.application.UsernameCaseChecker
import io.github.misdocumeno.domain.UsernameCaseCheckResult
import io.github.misdocumeno.infra.http.KtorAuthApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component

@Plugin(
    id = "auth-adapter",
    name = "AuthAdapter",
    version = "0.1.0",
    description = "External auth service adapter",
    authors = ["Mis"],
)
class VelocityAuthAdapterPlugin @Inject constructor(
    private val proxy: ProxyServer,
) {

    private lateinit var caseChecker: UsernameCaseChecker
    private lateinit var invalidator: SessionInvalidator

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    }

    @Subscribe
    fun onPreLogin(event: PreLoginEvent) = runBlocking {
        when (val result = caseChecker.check(event.username)) {
            UsernameCaseCheckResult.ExactMatch -> Unit

            UsernameCaseCheckResult.NotFound ->
                event.result = PreLoginEvent.PreLoginComponentResult.denied(
                    // TODO: better message, +translated, domain configurable by env
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
        scope.launch {
            invalidator.invalidate(event.player.username)
        }
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        scope.cancel()
    }
}