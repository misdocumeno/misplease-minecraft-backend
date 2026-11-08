package io.github.misdocumeno

import io.github.misdocumeno.infra.paper.PaperTeleportAskHandler
import io.github.misdocumeno.infra.paper.TeleportMessages
import io.github.misdocumeno.infra.paper.commands.TeleportAcceptCommand
import io.github.misdocumeno.infra.paper.commands.TeleportAllCommand
import io.github.misdocumeno.infra.paper.commands.TeleportAskCommand
import io.github.misdocumeno.infra.paper.commands.TeleportAskHereCommand
import io.github.misdocumeno.infra.paper.commands.TeleportCancelCommand
import io.github.misdocumeno.infra.paper.commands.TeleportCommand
import io.github.misdocumeno.infra.paper.commands.TeleportDenyCommand
import io.github.misdocumeno.infra.paper.commands.TeleportHereCommand
import io.github.misdocumeno.infra.redis.RedisAuthenticatedRepository
import io.github.misdocumeno.infra.redis.RedisLocationResolver
import io.github.misdocumeno.infra.redis.RedisTeleportAskAdapter
import io.github.misdocumeno.infra.redis.RedisTeleportAskListener
import io.github.misdocumeno.infra.redis.RedisTeleportRequestSender
import io.github.misdocumeno.infra.redis.RedisUsernameRepository
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.bukkit.plugin.java.JavaPlugin

class BackendTeleportCommandPlugin : JavaPlugin() {

    private lateinit var client: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var connectionPubSub: StatefulRedisPubSubConnection<String, String>
    private lateinit var redis: RedisCommands<String, String>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // TODO: if the player is in the same server, avoid using redis at all
    override fun onEnable() {
        client = RedisClient.create(System.getenv("REDIS_URL"))
        connection = client.connect()
        connectionPubSub = client.connectPubSub()
        redis = connection.sync()

        val json = Json {
            ignoreUnknownKeys = true
        }

        val messages = TeleportMessages(this)

        val usernames = RedisUsernameRepository(redis, this)
        val authenticated = RedisAuthenticatedRepository(redis)
        val locations = RedisLocationResolver(authenticated, connectionPubSub, connection, json)
        locations.start()
        val teleporter = RedisTeleportRequestSender(connection, json)

        getCommand("tp")!!.setExecutor(TeleportCommand(this, usernames, locations, teleporter, scope, messages))
        getCommand("tphere")!!.setExecutor(TeleportHereCommand(usernames, teleporter, messages))
        getCommand("tpall")!!.setExecutor(TeleportAllCommand(this, usernames, locations, teleporter, scope, messages))

        getCommand("tpa")!!.setExecutor(TeleportAskCommand(this, scope, usernames, redis, json, messages))
        getCommand("tpahere")!!.setExecutor(TeleportAskHereCommand(this, scope, usernames, redis, json, messages))

        getCommand("tpaccept")!!.setExecutor(
            TeleportAcceptCommand(this, usernames, locations, teleporter, redis, scope, messages)
        )
        getCommand("tpdeny")!!.setExecutor(TeleportDenyCommand(this, scope, usernames, redis, messages))
        getCommand("tpacancel")!!.setExecutor(TeleportCancelCommand(this, scope, usernames, redis, messages))

        val handler = PaperTeleportAskHandler(this, messages)
        val adapter = RedisTeleportAskAdapter(handler, json)
        RedisTeleportAskListener(connectionPubSub, adapter).start()
    }

    override fun onDisable() {
        connection.close()
        connectionPubSub.close()
        client.shutdown()
    }
}
