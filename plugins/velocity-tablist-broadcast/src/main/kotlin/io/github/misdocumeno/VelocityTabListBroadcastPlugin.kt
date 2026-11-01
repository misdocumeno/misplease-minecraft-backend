package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.model.AuthEvent
import io.github.misdocumeno.infra.redis.RedisAuthAdapter
import io.github.misdocumeno.infra.redis.RedisAuthListener
import io.github.misdocumeno.infra.redis.RedisAuthenticatedRepository
import io.github.misdocumeno.infra.velocity.GlobalTabListRebuilder
import io.github.misdocumeno.infra.velocity.VelocityAuthEventHandler
import io.github.misdocumeno.infra.velocity.VelocityEventListener
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Plugin(
    id = "tablist-broadcast",
    name = "TabListBroadcast",
    version = "0.1.0",
    description = "Broadcasts a global tablist with all players connected to any backend server",
)
class VelocityTabListBroadcastPlugin @Inject constructor(
    private val proxy: ProxyServer
) {

    private lateinit var redis: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var connectionPubSub: StatefulRedisPubSubConnection<String, String>

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        redis = RedisClient.create(System.getenv("REDIS_URL"))
        connection = redis.connect()
        connectionPubSub = redis.connectPubSub()

        val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            serializersModule = SerializersModule {
                polymorphic(AuthEvent::class) {
                    subclass(AuthEvent.Authenticated::class)
                    subclass(AuthEvent.Invalidated::class)
                }
            }
        }

        val authRepo = RedisAuthenticatedRepository(connection)
        val rebuilder = GlobalTabListRebuilder(proxy, authRepo)
        val handler = VelocityAuthEventHandler(rebuilder)
        val adapter = RedisAuthAdapter(handler, json)
        RedisAuthListener(connectionPubSub, adapter).start()

        proxy.eventManager.register(this, VelocityEventListener(authRepo, rebuilder))
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        connectionPubSub.close()
        connection.close()
        redis.shutdown()
    }
}