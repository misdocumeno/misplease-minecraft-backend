package io.github.misdocumeno

import io.github.misdocumeno.domain.ChatAudience
import io.github.misdocumeno.infra.paper.PaperChatBroadcastHandler
import io.github.misdocumeno.infra.paper.PaperChatListener
import io.github.misdocumeno.infra.paper.PaperChatRenderer
import io.github.misdocumeno.infra.paper.PaperUsernameRepository
import io.github.misdocumeno.infra.redis.RedisAuthenticatedRepository
import io.github.misdocumeno.infra.redis.RedisChatBroadcastAdapter
import io.github.misdocumeno.infra.redis.RedisChatIntentPublisher
import io.github.misdocumeno.infra.redis.RedisChatBroadcastListener
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.bukkit.plugin.java.JavaPlugin

class BackendChatAdapterPlugin : JavaPlugin() {

    private lateinit var redis: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>

    private lateinit var listener: RedisChatBroadcastListener

    override fun onEnable() {
        redis = RedisClient.create(System.getenv("REDIS_URL"))
        connection = redis.connect()

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

        val usernames = PaperUsernameRepository()
        val authRepo = RedisAuthenticatedRepository(connection, usernames)

        val renderer = PaperChatRenderer(server, authRepo)
        val broadcastHandler = PaperChatBroadcastHandler(renderer)
        val redisAdapter = RedisChatBroadcastAdapter(broadcastHandler, json)

        listener = RedisChatBroadcastListener(redis, redisAdapter)
        listener.start()

        val publisher = RedisChatIntentPublisher(connection, json)
        server.pluginManager.registerEvents(PaperChatListener(publisher), this)
    }

    override fun onDisable() {
        listener.stop()
        connection.close()
        redis.shutdown()
    }
}