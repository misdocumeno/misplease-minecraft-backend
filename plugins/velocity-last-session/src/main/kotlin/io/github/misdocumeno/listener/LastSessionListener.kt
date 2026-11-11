package io.github.misdocumeno.listener

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.LastSessionService
import org.slf4j.Logger

/** Read-only restore: backends own writes to player_last_session. */
class LastSessionListener(
    private val proxy: ProxyServer,
    private val service: LastSessionService,
    private val logger: Logger,
) {

    @Subscribe
    fun onPreConnect(event: ServerPreConnectEvent) {
        if (event.previousServer != null) return

        val player = event.player
        val last = service.getLastServer(player.uniqueId) ?: return
        val server = proxy.getServer(last).orElse(null)
        if (server == null) {
            logger.warn("last-session server '{}' for {} not registered on proxy", last, player.username)
            return
        }

        event.result = ServerPreConnectEvent.ServerResult.allowed(server)
    }
}
