package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.model.AuthEvent
import io.github.misdocumeno.infra.database.DatabaseFactory
import io.github.misdocumeno.infra.database.MailRepository
import io.github.misdocumeno.infra.redis.RedisAuthEvents
import io.github.misdocumeno.infra.redis.RedisAuthSet
import io.github.misdocumeno.infra.velocity.ListCommand
import io.github.misdocumeno.infra.velocity.MailCommand
import io.github.misdocumeno.infra.velocity.MailNotify
import io.github.misdocumeno.infra.velocity.MsgCommand
import io.github.misdocumeno.infra.velocity.ReplyCommand
import io.github.misdocumeno.infra.velocity.ReplyIndex
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.slf4j.Logger

@Plugin(
    id = "social",
    name = "Social",
    version = "0.1.0",
    description = "Network-wide /msg /reply /list /mail (replaces Essentials equivalents)",
    authors = ["Mis"],
)
class VelocitySocialPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private lateinit var redis: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var connectionPubSub: StatefulRedisPubSubConnection<String, String>

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        DatabaseFactory.initFromEnv()
        val mailRepo = MailRepository()
        val mailNotify = MailNotify(proxy, mailRepo)

        redis = RedisClient.create(System.getenv("REDIS_URL"))
        connection = redis.connect()
        connectionPubSub = redis.connectPubSub()
        val auth = RedisAuthSet(connection)
        val replies = ReplyIndex()

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
        RedisAuthEvents(connectionPubSub, json) { ev ->
            if (ev is AuthEvent.Authenticated) mailNotify.onAuthenticated(ev.username)
        }.start()

        val mgr = proxy.commandManager
        mgr.register(
            mgr.metaBuilder("msg").aliases("tell", "w", "m", "whisper", "t", "emsg", "pm", "ewhisper", "etell").plugin(this).build(),
            MsgCommand(proxy, auth, replies),
        )
        mgr.register(
            mgr.metaBuilder("reply").aliases("r", "er").plugin(this).build(),
            ReplyCommand(proxy, auth, replies),
        )
        mgr.register(
            mgr.metaBuilder("list").aliases("online", "playerlist", "who").plugin(this).build(),
            ListCommand(proxy, auth),
        )
        mgr.register(
            mgr.metaBuilder("mail").aliases("email").plugin(this).build(),
            MailCommand(proxy, auth, mailRepo),
        )
        logger.info("network /msg /reply /list /mail registered")
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        connectionPubSub.close()
        connection.close()
        redis.shutdown()
    }
}
