package io.github.misdocumeno.limbo

import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.VelocityLimboPlugin
import io.github.misdocumeno.domain.LoginApi
import io.github.misdocumeno.infra.http.KtorLoginApi
import io.github.misdocumeno.infra.limbo.LimboWorlds
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.LimboFactory
import net.elytrium.limboapi.api.command.LimboCommandMeta
import net.elytrium.limboapi.api.player.GameMode
import org.slf4j.Logger

/**
 * Wires our login flow onto LimboAPI.
 *
 * [VelocityLimboPlugin] declares `limboapi` as a hard dependency, so its classes
 * are on our classloader and can be used as normal types here.
 */
class LimboBootstrap(
    private val proxy: ProxyServer,
    private val plugin: VelocityLimboPlugin,
    private val limboApi: PluginContainer,
    private val logger: Logger,
) {

    fun start() {
        val factory = limboApi.instance.orElse(null) as? LimboFactory
            ?: error("limboapi is registered but does not expose a LimboFactory")

        val world = LimboWorlds.platform(factory)
        val limbo: Limbo = factory
            .createLimbo(world)
            .setName("limbo")
            .setGameMode(GameMode.ADVENTURE)
            .setViewDistance(4)
            .setSimulationDistance(2)
            // Authenticated players leave for a real backend; never pull them back.
            .setShouldRejoin(false)
            // Keep this true: `shouldRespawn` is what makes LimboAPI send the
            // position + spawn chunks on join. Turning it off leaves the client
            // on "Loading terrain" until it gives up and drops into the void.
            .setShouldRespawn(true)
            // Declare it in the limbo command tree, otherwise the client treats
            // `/login` as unknown (LimboAPI still routes the packet to onChat).
            .registerCommand(LimboCommandMeta(listOf("login")))

        logger.info(
            "limbo world: {} chunks, spawn {}/{}/{}, block at y={} is {}",
            world.chunks.size,
            world.spawnX,
            world.spawnY,
            world.spawnZ,
            LimboWorlds.FLOOR_Y,
            world.getBlock(0, LimboWorlds.FLOOR_Y, 0).modernStringID.ifEmpty { "air" },
        )

        val api: LoginApi = KtorLoginApi(
            HttpClient(CIO) {
                // Login runs while the player waits in the limbo; never let a
                // stuck auth call hold the scheduler thread forever.
                install(HttpTimeout) { requestTimeoutMillis = 10_000 }
            },
            System.getenv("AUTH_URL")?.takeIf { it.isNotBlank() } ?: "http://auth:3000",
            logger,
        )
        val entryServer = System.getenv("LIMBO_ENTRY_SERVER")?.takeIf { it.isNotBlank() } ?: "main"
        val timeoutSeconds = System.getenv("LIMBO_LOGIN_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L

        // First arg must be the plugin instance: Velocity resolves it with
        // PluginManager.ensurePluginContainer() and throws for any other object.
        proxy.eventManager.register(
            plugin,
            LoginLimboListener(
                proxy = proxy,
                scheduler = proxy.scheduler,
                plugin = plugin,
                limbo = limbo,
                api = api,
                entryServer = entryServer,
                timeoutSeconds = timeoutSeconds,
                logger = logger,
            ),
        )
        logger.info("login limbo ready (entry server: {}, timeout: {}s)", entryServer, timeoutSeconds)
    }
}
