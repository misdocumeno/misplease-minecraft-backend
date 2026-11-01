package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import io.github.misdocumeno.domain.AuthenticatedRepository

class VelocityEventListener(
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
        } else {
            // the player changed servers. that triggers PLAYER_INFO
            // in both backends, so we have to rebuild it again
            rebuilder.rebuildGlobalTabList()
        }
    }
}