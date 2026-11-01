package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.GlobalLocationResolver
import io.github.misdocumeno.domain.AuthenticatedRepository
import io.github.misdocumeno.domain.model.GlobalLocation
import io.github.misdocumeno.domain.model.LocationRequest
import io.github.misdocumeno.domain.model.LocationResponse
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class RedisLocationResolver(
    private val authenticated: AuthenticatedRepository,
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val redis: RedisCommands<String, String>,
    private val json: Json,
) : GlobalLocationResolver {

    private val pending = ConcurrentHashMap<Uuid, CompletableDeferred<LocationResponse>>()

    fun start() {
        connection.apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel == "player:location:response:rpc") {
                        val response = try {
                            json.decodeFromString<LocationResponse>(message)
                        } catch (e: Exception) {
                            System.err.println("[TeleportCommand] Error decoding: $message")
                            e.printStackTrace()
                            return
                        }
                        pending.remove(response.requestId)?.complete(response)
                    }
                }
            })
            async().subscribe("player:location:response:rpc")
        }
    }

    override suspend fun getPlayerLocation(username: String): GlobalLocation? {
        if (!authenticated.isAuthenticated(username)) return null

        val requestId = Uuid.random()
        val deferred = CompletableDeferred<LocationResponse>()
        pending[requestId] = deferred

        val request = LocationRequest(requestId, username)
        redis.publish("player:location:request:rpc", json.encodeToString(request))

        // this can only time out rarely, when a player is moving
        // between servers, and none of them responds with the location
        return try {
            withTimeout(3000L) {
                deferred.await().location
            }
        } catch (e: TimeoutCancellationException) {
            pending.remove(requestId)
            throw e
        }
    }
}