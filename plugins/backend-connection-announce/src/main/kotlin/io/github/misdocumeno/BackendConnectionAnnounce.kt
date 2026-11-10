package io.github.misdocumeno

import io.github.misdocumeno.domain.model.AuthEvent
import io.github.misdocumeno.domain.model.NetworkAnnounce
import io.github.misdocumeno.infra.paper.ConnectionEventListener
import io.github.misdocumeno.infra.paper.PaperAuthEventHandler
import io.github.misdocumeno.infra.paper.PaperNetworkAnnounceHandler
import io.github.misdocumeno.infra.redis.RedisAuthAdapter
import io.github.misdocumeno.infra.redis.RedisAuthListener
import io.github.misdocumeno.infra.redis.RedisNetworkAnnounceAdapter
import io.github.misdocumeno.infra.redis.RedisNetworkAnnounceListener
import io.github.misdocumeno.infra.redis.RedisNetworkAnnouncePublisher
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.bukkit.plugin.java.JavaPlugin

class BackendConnectionAnnounce : JavaPlugin() {

    private lateinit var redis: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var connectionPubSub: StatefulRedisPubSubConnection<String, String>

    override fun onEnable() {
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
                polymorphic(NetworkAnnounce::class) {
                    subclass(NetworkAnnounce.Death::class)
                }
            }
        }

        val authHandler = PaperAuthEventHandler(this, server, connection)
        RedisAuthListener(connectionPubSub, RedisAuthAdapter(authHandler, json)).start()

        val announcePublisher = RedisNetworkAnnouncePublisher(connection, json)
        val announceHandler = PaperNetworkAnnounceHandler(this, server, connection)
        RedisNetworkAnnounceListener(
            connectionPubSub,
            RedisNetworkAnnounceAdapter(announceHandler, json),
        ).start()

        server.pluginManager.registerEvents(ConnectionEventListener(announcePublisher), this)
    }

    override fun onDisable() {
        connectionPubSub.close()
        connection.close()
        redis.shutdown()
    }
}
