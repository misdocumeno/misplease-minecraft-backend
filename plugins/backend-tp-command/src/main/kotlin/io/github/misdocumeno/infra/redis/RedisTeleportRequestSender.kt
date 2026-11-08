package io.github.misdocumeno.infra.redis

import io.github.misdocumeno.application.Teleporter
import io.github.misdocumeno.domain.model.GlobalLocation
import io.github.misdocumeno.domain.model.TeleportFeedback
import io.github.misdocumeno.domain.model.TeleportRequest
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.json.Json

class RedisTeleportRequestSender(
    private val connection: StatefulRedisConnection<String, String>,
    private val json: Json,
) : Teleporter {

    override fun teleport(username: String, location: GlobalLocation, feedback: TeleportFeedback?) {
        val request = TeleportRequest(username, location, feedback)
        connection.async().publish("global_teleport:execute", json.encodeToString(request))
    }
}
