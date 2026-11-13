package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.domain.AuthenticatedRepository
import java.util.concurrent.TimeUnit

class VelocityEventListener(
    private val plugin: Any,
    private val proxy: ProxyServer,
    private val authRepo: AuthenticatedRepository,
    private val rebuilder: GlobalTabListRebuilder,
) {

    // IMPORTANT: ServerPostConnectEvent is required here.
    // earlier events (PostLogin, ServerConnected) are too early, and
    // the backend would send a PLAYER_INFO packet, overwriting our tablist
    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        if (!authRepo.isAuthenticated(event.player.username)) {
            // the player has just connected.
            // hide players until they /login with valid credentials
            event.player.tabList.clearAll()
            return
        }
        // Delay so currentServer / local PLAYER_INFO are settled (empty tab on
        // first tech join otherwise).
        proxy.scheduler
            .buildTask(plugin, java.lang.Runnable { rebuilder.rebuildGlobalTabList() })
            .delay(250, TimeUnit.MILLISECONDS)
            .schedule()
    }
}
