package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.infra.redis.RedisTeleportAdapter
import io.github.misdocumeno.infra.redis.RedisTeleportListener
import io.github.misdocumeno.infra.velocity.VelocityTeleportHandler
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json

@Plugin(
    id = "global-teleporter",
    name = "GlobalTeleporter",
    version = "0.1.0",
    description = "Moves the player to the appropriate server during a global teleport request",
)
class VelocityGlobalTeleporterPlugin @Inject constructor(
    private val proxy: ProxyServer,
) {

    private lateinit var redis: RedisClient
    private lateinit var connectionPubSub: StatefulRedisPubSubConnection<String, String>

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        redis = RedisClient.create(System.getenv("REDIS_URL"))
        connectionPubSub = redis.connectPubSub()

        val json = Json {
            ignoreUnknownKeys = true
        }

        val handler = VelocityTeleportHandler(proxy)
        val adapter = RedisTeleportAdapter(handler, json)
        RedisTeleportListener(connectionPubSub, adapter).start()
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        connectionPubSub.close()
        redis.shutdown()
    }
}