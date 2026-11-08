package io.github.misdocumeno

import io.github.misdocumeno.infra.paper.PaperLocationHandler
import io.github.misdocumeno.infra.redis.RedisLocationAdapter
import io.github.misdocumeno.infra.redis.RedisLocationListener
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json
import org.bukkit.plugin.java.JavaPlugin

class BackendLocationResolverPlugin : JavaPlugin() {

    private lateinit var client: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var connectionPubSub: StatefulRedisPubSubConnection<String, String>

    override fun onEnable() {
        client = RedisClient.create(System.getenv("REDIS_URL"))
        connection = client.connect()
        connectionPubSub = client.connectPubSub()

        val json = Json {
            ignoreUnknownKeys = true
        }

        val handler = PaperLocationHandler()
        val adapter = RedisLocationAdapter(this, handler, connection, json)
        RedisLocationListener(connectionPubSub, adapter).start()
    }

    override fun onDisable() {
        connectionPubSub.close()
        connection.close()
        client.shutdown()
    }
}
