package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Plugin(
    id = "presence",
    name = "Presence",
    version = "0.1.0",
    description = "Publish proxy empty/player-count for restarter and CD (Redis + /data/presence.json)",
    authors = ["Mis"],
)
class VelocityPresencePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDir: Path,
) {
    private val json = Json { encodeDefaults = true }
    private val playerServer = ConcurrentHashMap<UUID, String>()

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
                logger.warn("redis unavailable for presence: ${e.message}")
            }
        }
        publish()
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        playerServer.clear()
        publish()
        connection?.close()
        redis?.shutdown()
    }

    @Subscribe
    fun onConnected(event: ServerConnectedEvent) {
        playerServer[event.player.uniqueId] = event.server.serverInfo.name
        publish()
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        playerServer.remove(event.player.uniqueId)
        publish()
    }

    private fun publish() {
        val byServer = playerServer.values.groupingBy { it }.eachCount()
        val total = playerServer.size
        val proxyPayload = Presence(
            scope = "proxy",
            server = "proxy",
            count = total,
            empty = total == 0,
            updatedAt = Instant.now().toString(),
            servers = byServer,
        )
        val body = json.encodeToString(proxyPayload)

        for (path in listOf(Path.of("/data/presence.json"), dataDir.resolve("presence.json"))) {
            try {
                Files.createDirectories(path.parent)
                Files.writeString(path, body + "\n")
            } catch (e: Exception) {
                logger.warn("could not write $path: ${e.message}")
            }
        }

        val conn = connection ?: return
        try {
            val sync = conn.sync()
            sync.set("misplease:presence:proxy", body)
            sync.publish("misplease:presence", body)
            for ((name, n) in byServer) {
                val serverBody = json.encodeToString(
                    Presence(
                        scope = "server",
                        server = name,
                        count = n,
                        empty = n == 0,
                        updatedAt = Instant.now().toString(),
                    ),
                )
                // Do not overwrite backend keys while that backend is up; proxy view is separate.
                sync.set("misplease:presence:proxy:server:$name", serverBody)
            }
        } catch (e: Exception) {
            logger.warn("redis presence publish failed: ${e.message}")
        }
    }
}

@Serializable
data class Presence(
    val scope: String,
    val server: String,
    val count: Int,
    val empty: Boolean,
    val updatedAt: String,
    val servers: Map<String, Int> = emptyMap(),
)
