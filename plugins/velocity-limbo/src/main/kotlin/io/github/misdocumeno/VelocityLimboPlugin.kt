package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.LoginApi
import io.github.misdocumeno.infra.http.KtorLoginApi
import io.github.misdocumeno.infra.limbo.LimboWorlds
import io.github.misdocumeno.limbo.LoginLimboListener
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.LimboFactory
import net.elytrium.limboapi.api.player.GameMode
import org.slf4j.Logger

/**
 * Login lives in the proxy.
 *
 * Every player who connects lands in a virtual limbo (LimboAPI) instead of a
 * Paper server, authenticates with `/login`, and only then is connected to a
 * backend. Until that point there is no Paper player, no tablist entry and no
 * world state to corrupt — the whole class of "backend player exists but is not
 * authenticated" bugs disappears by construction.
 *
 * The auth service owns the session: a successful `POST /login` writes the same
 * Redis session the backends read, so no second implementation of auth exists.
 */
@Plugin(
    id = "limbo",
    name = "Limbo",
    version = "0.1.0",
    description = "Proxy-side login limbo",
    authors = ["Mis"],
    dependencies = [Dependency(id = "limboapi", optional = true)],
)
class VelocityLimboPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {

    private lateinit var limbo: Limbo

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        val container = proxy.pluginManager.getPlugin("limboapi").orElse(null)
        if (container == null) {
            logger.error("limboapi is not installed — players cannot log in, limbo disabled")
            return
        }
        val factory = container.instance.orElse(null) as? LimboFactory
        if (factory == null) {
            logger.error("limboapi has no LimboFactory instance — limbo disabled")
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
