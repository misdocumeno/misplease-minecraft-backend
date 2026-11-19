package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger
import java.util.concurrent.TimeUnit

/**
 * Fronts hibernated backends.
 *
 * A frozen backend (see `tools/hibernate`) cannot answer a connect, but its
 * kernel still accepts TCP, so the connection sits in the listen queue while the
 * host agent thaws it. All this plugin has to do is *ask*: on a connect to a
 * server the host agent has published as `asleep`, drop a wake request in Redis
 * and tell the player what is happening. Velocity's
 * `[advanced] connection-timeout` (10 s) is what covers the thaw (measured 1.2 s).
 *
 * Deliberately does not deny the connection: denying would send the player back
 * to the limbo (or kick them on the first join) for a one-second wait.
 */
@Plugin(
    id = "wake",
    name = "Wake",
    version = "0.1.0",
    description = "Wakes hibernated backends before the proxy connects to them",
    authors = ["Mis"],
)
class VelocityWakePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {

    private val mm = MiniMessage.miniMessage()
    private val json = Json { ignoreUnknownKeys = true }

    /** Server names the host agent currently has frozen. */
    @Volatile
    private var asleep: Set<String> = emptySet()

    private var redis: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        System.getenv("REDIS_URL")?.takeIf { it.isNotBlank() }?.let { url ->
            try {
                val client = RedisClient.create(url)
                redis = client
                connection = client.connect()
            } catch (e: Exception) {
                logger.warn("redis unavailable for wake gate: ${e.message}")
            }
        }
        // Poll instead of reading in the connect path: the event fires on the
        // player's thread and must not wait on a network round trip.
        proxy.scheduler
            .buildTask(this, Runnable { refresh() })
            .repeat(2, TimeUnit.SECONDS)
            .schedule()
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        connection?.close()
        redis?.shutdown()
    }

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val server = event.originalServer.serverInfo.name
        if (server !in asleep) return

        val conn = connection ?: return
        try {
            // NX + TTL: repeated attempts in the same second are one wake.
            conn.sync().set(
                WAKE_PREFIX + server,
                System.currentTimeMillis().toString(),
                SetArgs.Builder.nx().ex(120),
            )
            logger.info("{} requested wake of {}", event.player.username, server)
        } catch (e: Exception) {
            logger.warn("redis wake request failed for {}: {}", server, e.message)
        }
        event.player.sendActionBar(
            mm.deserialize("$PREFIX<gray>Despertando <white>$server</white>…</gray>"),
        )
    }

    private fun refresh() {
        val conn = connection ?: return
        try {
            val raw = conn.sync().get(STATE_KEY) ?: return
            val state = json.parseToJsonElement(raw) as? JsonObject ?: return
            asleep = state.entries
                .filter { (_, value) -> value.jsonPrimitive.content == "asleep" }
                .map { (name, _) -> name }
                .toSet()
        } catch (e: Exception) {
            // A stale set is worse than an empty one: an empty set just means
            // "do not pre-wake", the connect still works once the backend thaws.
            asleep = emptySet()
            logger.warn("redis hibernate state read failed: ${e.message}")
        }
    }

    private companion object {
        /** Keep in sync with `tools/hibernate/agent.py`. */
        const val STATE_KEY = "misplease:hibernate:state"
        const val WAKE_PREFIX = "misplease:hibernate:wake:"

        /** Same prefix as the rest of the network. */
        const val PREFIX =
            "<dark_purple><bold>MisPlease</bold></dark_purple><dark_gray><bold> » </bold></dark_gray>"
    }
}
