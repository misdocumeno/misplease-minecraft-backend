package io.github.misdocumeno.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.LastServerService
import org.slf4j.Logger

class LastServerListener(
    private val proxy: ProxyServer,
    private val service: LastServerService,
    private val logger: Logger
) {

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val player = event.player
        val server = event.server.serverInfo.name
        service.record(player.uniqueId, server)
    }

    @Subscribe
    fun onPreConnect(event: ServerPreConnectEvent) {
        // only restore location when actually connecting to the server
        if (event.previousServer != null) return

        val player = event.player
        val last = service.getLastServer(player.uniqueId) ?: return
        val server = proxy.getServer(last).orElse(null) ?: return

        event.result = ServerPreConnectEvent.ServerResult.allowed(server)
    }
}