package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fronts hibernated backends.
 *
 * A frozen backend (see `tools/hibernate`) cannot answer a connect, but its
 * kernel still accepts TCP, so the connection sits in the listen queue while the
 * host agent thaws it. All this plugin has to do is *ask*: on a connect to a
 * server the host agent has published as `asleep`, drop a wake request in Redis
 * and tell the player what is happening. Velocity's
 * `[advanced] connection-timeout` is what covers the thaw (measured 1-18 s).
 *
 * Deliberately does not deny the connection: denying would send the player back
 * to the limbo (or kick them on the first join) for a one-second-to-20-second
 * wait. The action bar repeats until the player is actually on the target
 * server, so the "waking up" message does not fade while they wait.
 */
@Plugin(
    id = "wake",
    name = "Wake",
    version = "0.2.0",
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

    /** A connect to [target] is in flight; [previous] is where the player was. */
    private data class Pending(val target: String, val previous: String?)

    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val tasks = ConcurrentHashMap<UUID, ScheduledTask>()

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
        tasks.values.forEach { it.cancel() }
        tasks.clear()
        pending.clear()
        connection?.close()
        redis?.shutdown()
    }

    /**
     * `order = LAST` matters: `velocity-last-session` rewrites the target on the
     * first connect of a session (`try = ["main"]` offers main, then gets
     * redirected to the player's real backend). Reading `originalServer` here
     * woke main on every single login, including players whose target was tech.
     */
    @Subscribe(order = PostOrder.LAST)
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        val server = event.result.server.orElse(null)?.serverInfo?.name ?: return
        if (server !in asleep) return

        val player = event.player
        val existing = pending[player.uniqueId]
        when (WakeDecision.onPreConnect(server, existing?.target)) {
            WakeDecision.Action.DENY -> {
                // Two backends cannot be connected at once. Keep the first wake
                // and tell the player instead of racing two Velocity connects.
                val busy = existing?.target ?: return
                event.result = ServerPreConnectEvent.ServerResult.denied()
                player.sendMessage(
                    mm.deserialize(
                        "$PREFIX<gray>Ya estás conectando a <white>$busy</white>. " +
                            "Esperá un momento.</gray>",
                    ),
                )
            }

            WakeDecision.Action.START,
            WakeDecision.Action.CONTINUE -> {
                val created = requestWake(server)
                logger.info(
                    "{} requested wake of {} (new={})",
                    player.username,
                    server,
                    created,
                )
                if (existing?.target != server) {
                    startProgress(player, server, event.previousServer?.serverInfo?.name)
                }
            }
        }
    }

    /** The player is on the target (or somewhere else): the wait is over. */
    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        stop(event.player.uniqueId)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        stop(event.player.uniqueId)
    }

    /**
     * If the backend still fails (crashed, not merely slow), put the player back
     * where they came from instead of dropping them to the multiplayer menu.
     */
    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        val waiting = pending[event.player.uniqueId] ?: return
        when (
            val action = WakeDecision.onKick(
                kickedServer = event.server.serverInfo.name,
                pendingTarget = waiting.target,
                previous = waiting.previous,
            )
        ) {
            WakeDecision.KickAction.Ignore -> return
            WakeDecision.KickAction.Disconnect -> stop(event.player.uniqueId)
            is WakeDecision.KickAction.Redirect -> {
                stop(event.player.uniqueId)
                val back = proxy.getServer(action.server).orElse(null) ?: return
                event.result = KickedFromServerEvent.RedirectPlayer.create(
                    back,
                    mm.deserialize(
                        "$PREFIX<gray>No pudimos conectarte a " +
                            "<white>${waiting.target}</white>. Volviste a " +
                            "<white>${back.serverInfo.name}</white>.</gray>",
                    ),
                )
            }
        }
    }

    private fun startProgress(player: Player, target: String, previous: String?) {
        val id = player.uniqueId
        pending[id] = Pending(target, previous)
        player.sendMessage(
            mm.deserialize(
                buildString {
                    append(PREFIX)
                    append("<gray>Despertando <white>")
                    append(target)
                    append("</white>…")
                    if (previous != null) {
                        append(" <dark_gray>Podés seguir jugando mientras termina.</dark_gray>")
                    }
                    append("</gray>")
                },
            ),
        )

        var frame = 0
        val task = proxy.scheduler
            .buildTask(
                this,
                Runnable {
                    val still = pending[id]
                    if (still == null || still.target != target || !player.isActive) {
                        tasks.remove(id)?.cancel()
                        return@Runnable
                    }
                    if (frame >= MAX_FRAMES) {
                        player.sendMessage(
                            mm.deserialize(
                                "$PREFIX<gray>No pudimos despertar <white>$target</white> a " +
                                    "tiempo. Probá de nuevo.</gray>",
                            ),
                        )
                        stop(id)
                        return@Runnable
                    }
                    player.sendActionBar(actionBar(target, frame++))
                },
            )
            .repeat(FRAME_MILLIS, TimeUnit.MILLISECONDS)
            .schedule()
        tasks.put(id, task)?.cancel() // replace a stale task if one survived
    }

    private fun stop(id: UUID) {
        pending.remove(id)
        tasks.remove(id)?.cancel()
    }

    /** Alternating colour and animated dots, so the wait visibly progresses. */
    private fun actionBar(target: String, frame: Int): Component {
        val dots = ".".repeat((frame / 2) % 3 + 1)
        val colour = if (frame % 2 == 0) "<gray>" else "<light_purple>"
        val close = if (frame % 2 == 0) "</gray>" else "</light_purple>"
        return mm.deserialize("$colour Despertando <white>$target</white>$dots$close")
    }

    /** Returns true when this call actually created the wake key. */
    private fun requestWake(server: String): Boolean {
        val conn = connection ?: return false
        return try {
            // NX + TTL: repeated attempts while the same freeze is in progress
            // are one wake, even if the player spams the warp command.
            conn.sync().set(
                WAKE_PREFIX + server,
                System.currentTimeMillis().toString(),
                SetArgs.Builder.nx().ex(120),
            ) == "OK"
        } catch (e: Exception) {
            logger.warn("redis wake request failed for {}: {}", server, e.message)
            false
        }
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

        const val FRAME_MILLIS = 400L
        const val MAX_FRAMES = 150 // 60 s, comfortably past connection-timeout

        /** Same prefix as the rest of the network. */
        const val PREFIX =
            "<dark_purple><bold>MisPlease</bold></dark_purple><dark_gray><bold> » </bold></dark_gray>"
    }
}
