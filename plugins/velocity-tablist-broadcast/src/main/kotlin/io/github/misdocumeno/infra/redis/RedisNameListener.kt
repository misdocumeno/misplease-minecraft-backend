package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.domain.model.TabListNameUpdate
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.json.Json

/**
 * Backends publish their display name (nickname + LuckPerms colours) here so the
 * proxy tab list can show the same name chat does. The proxy does not have a
 * backend `Player`, so this is the only way it can see `/nickname`.
 */
class RedisNameListener(
    private val connection: StatefulRedisPubSubConnection<String, String>,
    private val json: Json,
    private val onUpdate: (TabListNameUpdate) -> Unit,
) {

    fun start() {
        connection.apply {
            addListener(object : RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) {
                    if (channel != CHANNEL) return
                    val update = runCatching {
                        json.decodeFromString<TabListNameUpdate>(message)
                    }.getOrNull() ?: return
                    onUpdate(update)
                }
            })
            async().subscribe(CHANNEL)
        }
    }

    companion object {
        const val CHANNEL = "misplease:tablist:name"
    }
}
