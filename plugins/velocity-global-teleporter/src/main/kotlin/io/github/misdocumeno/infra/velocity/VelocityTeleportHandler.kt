package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.application.TeleportHandler
import io.github.misdocumeno.domain.model.TeleportRequest
import kotlin.uuid.toKotlinUuid

class VelocityTeleportHandler(
    private val proxy: ProxyServer,
) : TeleportHandler {

    override fun handle(event: TeleportRequest) {
        val player = proxy.allPlayers.firstOrNull { it.username == event.username }
            ?: error("[GlobalTeleporter] Invalid player name: ${event.username}")

        val server = proxy.allServers.firstOrNull { it.serverInfo.name == event.location.server }
            ?: error("[GlobalTeleporter] Invalid server: ${event.location.server}")

        val alreadyOnServer = player.currentServer
            .map { it.serverInfo.name == event.location.server }
            .orElse(false)

        if (alreadyOnServer) {
            return
        }

        player.createConnectionRequest(server).fireAndForget()
    }
}