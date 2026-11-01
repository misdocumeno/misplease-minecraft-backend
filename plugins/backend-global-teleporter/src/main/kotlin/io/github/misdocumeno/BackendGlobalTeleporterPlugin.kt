package io.github.misdocumeno

import io.github.misdocumeno.domain.model.TeleportRequest
import io.github.misdocumeno.infra.paper.PaperEventListener
import io.github.misdocumeno.infra.paper.PaperTeleportHandler
import io.github.misdocumeno.infra.redis.RedisTeleportAdapter
import io.github.misdocumeno.infra.redis.RedisTeleportListener
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

class BackendGlobalTeleporterPlugin : JavaPlugin() {

    private lateinit var redis: RedisClient
    private lateinit var connectionPubSub: StatefulRedisPubSubConnection<String, String>

    override fun onEnable() {
        redis = RedisClient.create(System.getenv("REDIS_URL"))
        connectionPubSub = redis.connectPubSub()

        val json = Json {
            ignoreUnknownKeys = true
        }

        val serverId = System.getenv("SERVER_ID")
            ?: error("SERVER_ID env variable not set.")

        val pending = ConcurrentHashMap<String, TeleportRequest>()
        val handler = PaperTeleportHandler(this, serverId, pending)
        val adapter = RedisTeleportAdapter(handler, json)
        RedisTeleportListener(connectionPubSub, adapter).start()
        server.pluginManager.registerEvents(PaperEventListener(pending, handler), this)
    }

    override fun onDisable() {
        connectionPubSub.close()
        redis.shutdown()
    }
}