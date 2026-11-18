package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.protocol.AppliedMessage
import io.github.misdocumeno.domain.protocol.ApplyMessage
import io.github.misdocumeno.domain.protocol.OpenInvMessage
import io.github.misdocumeno.domain.protocol.OpenInvProtocol
import io.github.misdocumeno.domain.protocol.RequestStateMessage
import io.github.misdocumeno.domain.protocol.StateMessage
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Redis link for OpenInv: cross-server state/apply RPC plus the proxy presence
 * feed that tells where a target is online.
 *
 * Optional by design. Without REDIS_URL (or with Redis down) [available] is
 * false, nothing here blocks a server start, and callers only serve players
 * online on this backend.
 */
class RedisTransport(
    private val serverId: String,
    private val logger: Logger,
    private val onRequest: (OpenInvMessage) -> Unit,
    private val onPresence: (Map<String, String>) -> Unit,
) {

    private val json: Json = OpenInvProtocol.json
    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null
    private var pubsub: StatefulRedisPubSubConnection<String, String>? = null

    private val pending = ConcurrentHashMap<String, CompletableFuture<StateMessage>>()
    private val applied = ConcurrentHashMap<String, CompletableFuture<AppliedMessage>>()

    /** uuid -> server id, from the proxy presence snapshot. */
    @Volatile
    var presence: Map<String, String> = emptyMap()
        private set

    val available: Boolean
        get() = connection?.isOpen == true

    fun start() {
        val url = System.getenv("REDIS_URL")?.takeIf { it.isNotBlank() }
        if (url == null) {
            logger.warning("REDIS_URL unset; cross-server OpenInv disabled")
            return
        }
        try {
            val created = RedisClient.create(url)
            client = created
            connection = created.connect()
            pubsub =
                created.connectPubSub().apply {
                    addListener(
                        object : RedisPubSubAdapter<String, String>() {
                            override fun message(channel: String, message: String) {
                                onMessage(channel, message)
                            }
                        },
                    )
                    async().subscribe(OpenInvProtocol.REQUEST_CHANNEL, OpenInvProtocol.RESPONSE_CHANNEL, OpenInvProtocol.PRESENCE_CHANNEL)
                }
            connection?.sync()?.get(OpenInvProtocol.PRESENCE_KEY)?.let(::readPresence)
        } catch (error: Exception) {
            logger.warning("Redis unavailable for OpenInv: ${error.message}")
            stop()
        }
    }

    fun stop() {
        pending.values.forEach { it.cancel(true) }
        pending.clear()
        applied.values.forEach { it.cancel(true) }
        applied.clear()
        runCatching { pubsub?.close() }
        runCatching { connection?.close() }
        runCatching { client?.shutdown() }
        pubsub = null
        connection = null
        client = null
    }

    /** Blocking state read; call from the profile executor, never the main thread. */
    fun requestState(message: RequestStateMessage, timeoutMillis: Long = 3000L): StateMessage? {
        val connection = connection ?: return null
        val future = CompletableFuture<StateMessage>()
        pending[message.sessionId] = future
        return try {
            connection.async().publish(OpenInvProtocol.REQUEST_CHANNEL, json.encodeToString(OpenInvMessage.serializer(), message))
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            null
        } finally {
            pending.remove(message.sessionId, future)
        }
    }

    /** Fire-and-forget write; the next state read confirms it. */
    fun sendApply(message: ApplyMessage) {
        val connection = connection ?: return
        connection.async().publish(OpenInvProtocol.REQUEST_CHANNEL, json.encodeToString(OpenInvMessage.serializer(), message))
    }

    fun replyApplied(message: AppliedMessage) = publishResponse(message)

    fun replyState(message: StateMessage) = publishResponse(message)

    private fun publishResponse(message: OpenInvMessage) {
        val connection = connection ?: return
        connection.async().publish(OpenInvProtocol.RESPONSE_CHANNEL, json.encodeToString(OpenInvMessage.serializer(), message))
    }

    private fun onMessage(channel: String, raw: String) {
        when (channel) {
            OpenInvProtocol.PRESENCE_CHANNEL -> readPresence(raw)
            OpenInvProtocol.REQUEST_CHANNEL -> {
                val message =
                    runCatching { json.decodeFromString(OpenInvMessage.serializer(), raw) }.getOrElse {
                        logger.fine("bad OpenInv request: ${it.message}")
                        return
                    }
                when (message) {
                    is RequestStateMessage -> if (message.server == serverId) onRequest(message)
                    is ApplyMessage -> if (message.server == serverId) onRequest(message)
                    // Requests for other backends are ignored; responses are not handled here.
                    else -> Unit
                }
            }

            OpenInvProtocol.RESPONSE_CHANNEL -> {
                when (val message = runCatching { json.decodeFromString(OpenInvMessage.serializer(), raw) }.getOrNull()) {
                    is StateMessage -> pending.remove(message.sessionId)?.complete(message)
                    is AppliedMessage -> applied.remove(message.sessionId)?.complete(message)
                    else -> Unit
                }
            }
        }
    }

    private fun readPresence(raw: String) {
        val players =
            runCatching {
                json.parseToJsonElement(raw).jsonObject["players"]?.jsonArray.orEmpty()
                    .mapNotNull { element ->
                        val entry = element.jsonObject
                        val uuid = entry["uuid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val server = entry["server"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        uuid to server
                    }
            }.getOrDefault(emptyList())
        if (players.isEmpty() && runCatching { json.parseToJsonElement(raw).jsonObject["scope"]?.jsonPrimitive?.contentOrNull }.getOrNull() != "proxy") {
            return
        }
        val next = players.toMap()
        val changed = next != presence
        presence = next
        if (changed) onPresence(next)
    }
}
