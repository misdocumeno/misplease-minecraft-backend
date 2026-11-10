package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.infra.redis.RedisAuthSet
import io.github.misdocumeno.infra.velocity.ListCommand
import io.github.misdocumeno.infra.velocity.MsgCommand
import io.github.misdocumeno.infra.velocity.ReplyCommand
import io.github.misdocumeno.infra.velocity.ReplyIndex
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.slf4j.Logger

@Plugin(
    id = "social",
    name = "Social",
    version = "0.1.0",
    description = "Network-wide /msg /reply /list (replaces Essentials equivalents)",
    authors = ["Mis"],
)
class VelocitySocialPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private lateinit var redis: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        redis = RedisClient.create(System.getenv("REDIS_URL"))
        connection = redis.connect()
        val auth = RedisAuthSet(connection)
        val replies = ReplyIndex()

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
        logger.info("network /msg /reply /list registered")
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        connection.close()
        redis.shutdown()
    }
}
