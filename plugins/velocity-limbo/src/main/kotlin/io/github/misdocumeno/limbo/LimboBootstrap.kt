package io.github.misdocumeno.limbo

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.LoginApi
import io.github.misdocumeno.infra.http.KtorLoginApi
import io.github.misdocumeno.infra.limbo.LimboWorlds
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.LimboFactory
import net.elytrium.limboapi.api.player.GameMode
import org.slf4j.Logger

/**
 * Everything that touches LimboAPI. Loaded only when the limboapi plugin is
 * present, so a proxy without it can still load the [io.github.misdocumeno.VelocityLimboPlugin]
 * and simply not use a limbo.
 */
class LimboBootstrap(
    private val proxy: ProxyServer,
    private val limboApi: PluginContainer,
    private val logger: Logger,
) {

    private lateinit var limbo: Limbo

    fun start() {
        val factory = limboApi.instance.orElse(null) as? LimboFactory
        if (factory == null) {
            logger.error("limboapi has no LimboFactory instance — login limbo disabled")
            return
        }

        limbo = factory
            .createLimbo(LimboWorlds.platform(factory))
            .setName("limbo")
            .setGameMode(GameMode.ADVENTURE)
            .setViewDistance(4)
            .setSimulationDistance(2)
            // Authenticated players leave for a real backend; never pull them back.
            .setShouldRejoin(false)
            .setShouldRespawn(false)

        val api: LoginApi = KtorLoginApi(
            HttpClient(CIO) { install(ContentNegotiation) { json() } },
            System.getenv("AUTH_URL")?.takeIf { it.isNotBlank() } ?: "http://auth:3000",
        )
        val entryServer = System.getenv("LIMBO_ENTRY_SERVER")?.takeIf { it.isNotBlank() } ?: "main"
        val timeoutSeconds = System.getenv("LIMBO_LOGIN_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L

        proxy.eventManager.register(
            this,
            LoginLimboListener(
                proxy = proxy,
                scheduler = proxy.scheduler,
                plugin = this,
                limbo = limbo,
                api = api,
                entryServer = entryServer,
                timeoutSeconds = timeoutSeconds,
                logger = logger,
            ),
        )
        logger.info("login limbo ready (entry server: {}, timeout: {}s)", entryServer, timeoutSeconds)
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        if (::limbo.isInitialized) {
            limbo.dispose()
        }
    }
}
