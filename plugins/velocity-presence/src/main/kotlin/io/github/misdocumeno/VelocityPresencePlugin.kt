package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Plugin(
    id = "presence",
    name = "Presence",
    version = "0.1.1",
    description = "Publish proxy presence (counts + online players) to Redis for CD and admin",
    authors = ["Mis"],
)
class VelocityPresencePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private val json = Json { encodeDefaults = true }
    private val playerServer = ConcurrentHashMap<UUID, String>()

    private var redisUrl: String? = null
    private var redis: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    /**
     * When the *state* last changed, not when it was last written.
     *
     * Consumers read this as "empty since": the deploy empty gate wants 60 s
     * without players and hibernate seeds its idle timer from it. The heartbeat
     * therefore republishes the same timestamp instead of bumping it.
     */
    private var lastChangeAt: Instant = Instant.now()

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        redisUrl = System.getenv("REDIS_URL")?.takeIf { it.isNotBlank() }
        connect()
        publish()
        // Redis is ephemeral in this stack (a plain container with no volume), so
        // a restart erases the key the deploy gate and hibernate read. Without a
        // heartbeat neither would ever see presence again until someone joins and
        // leaves: deploys would wait forever and idle backends would never freeze.
        proxy.scheduler
            .buildTask(this, Runnable { publish() })
            .repeat(Duration.ofSeconds(HEARTBEAT_SECONDS))
            .schedule()
        logger.info("presence heartbeat every ${HEARTBEAT_SECONDS}s")
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        playerServer.clear()
        lastChangeAt = Instant.now()
        publish()
        connection?.close()
        redis?.shutdown()
    }

    @Subscribe
    fun onConnected(event: ServerConnectedEvent) {
        playerServer[event.player.uniqueId] = event.server.serverInfo.name
        lastChangeAt = Instant.now()
        publish()
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        playerServer.remove(event.player.uniqueId)
        lastChangeAt = Instant.now()
        publish()
    }

    private fun connect() {
        val url = redisUrl ?: return
        if (connection != null) return
        try {
            val client = RedisClient.create(url)
            redis = client
            connection = client.connect()
        } catch (e: Exception) {
            logger.warn("redis unavailable for presence: ${e.message}")
        }
    }

    private fun publish() {
        // Retry the initial connect on the heartbeat: a Redis that was down when
        // the proxy booted must not leave presence missing forever.
        if (connection == null) connect()
        val conn = connection ?: return
        val byServer = playerServer.values.groupingBy { it }.eachCount()
        val total = playerServer.size
        val online =
            playerServer.mapNotNull { (uuid, server) ->
                val player = proxy.getPlayer(uuid).orElse(null) ?: return@mapNotNull null
                OnlinePlayer(
                    username = player.username,
                    uuid = uuid.toString(),
                    server = server,
                )
            }.sortedBy { it.username.lowercase() }
        val proxyPayload =
            Presence(
                scope = "proxy",
                server = "proxy",
                count = total,
                empty = total == 0,
                updatedAt = lastChangeAt.toString(),
                servers = byServer,
                players = online,
            )
        val body = json.encodeToString(proxyPayload)
        try {
            val sync = conn.sync()
            sync.set("misplease:presence:proxy", body)
            sync.publish("misplease:presence", body)
            for ((name, n) in byServer) {
                val serverPlayers = online.filter { it.server == name }
                val serverBody =
                    json.encodeToString(
                        Presence(
                            scope = "server",
                            server = name,
                            count = n,
                            empty = n == 0,
                            updatedAt = lastChangeAt.toString(),
                            players = serverPlayers,
                        ),
                    )
                // Do not overwrite backend keys while that backend is up; proxy view is separate.
                sync.set("misplease:presence:proxy:server:$name", serverBody)
            }
        } catch (e: Exception) {
            logger.warn("redis presence publish failed: ${e.message}")
        }
    }

    private companion object {
        const val HEARTBEAT_SECONDS = 60L
    }
}

@Serializable
data class OnlinePlayer(
    val username: String,
    val uuid: String,
    val server: String,
)

@Serializable
data class Presence(
    val scope: String,
    val server: String,
    val count: Int,
    val empty: Boolean,
    val updatedAt: String,
    val servers: Map<String, Int> = emptyMap(),
    val players: List<OnlinePlayer> = emptyList(),
)
