package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.application.ChatAudienceResolver
import io.github.misdocumeno.application.ChatFormatter
import io.github.misdocumeno.application.ChatIntentHandler
import io.github.misdocumeno.domain.model.ChatAudience
import io.github.misdocumeno.infra.redis.RedisAuthenticatedRepository
import io.github.misdocumeno.infra.redis.RedisChatBroadcaster
import io.github.misdocumeno.infra.redis.RedisChatIntentAdapter
import io.github.misdocumeno.infra.redis.RedisChatIntentListener
import io.github.misdocumeno.infra.velocity.VelocityUsernameRepository
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Plugin(
    id = "chat-control",
    name = "ChatControl",
    version = "0.1.0",
    description = "Global chat controller",
    authors = ["Mis"],
)
class VelocityCommunicationPlugin @Inject constructor(
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
                polymorphic(ChatAudience::class) {
                    subclass(ChatAudience.Global::class)
                    subclass(ChatAudience.SpecificPlayers::class)
                }
            }
        }

        val usernames = VelocityUsernameRepository(proxy)
        val authRepo = RedisAuthenticatedRepository(connection, usernames)

        val audienceResolver = ChatAudienceResolver()
        val formatter = ChatFormatter(usernames)
        val broadcaster = RedisChatBroadcaster(connection, json)
        val intentHandler = ChatIntentHandler(authRepo, audienceResolver, formatter, broadcaster)
        val intentAdapter = RedisChatIntentAdapter(intentHandler, json)
        RedisChatIntentListener(connectionPubSub, intentAdapter).start()
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        connectionPubSub.close()
        connection.close()
        redis.shutdown()
    }
}