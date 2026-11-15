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

    // ServerPostConnectEvent: earlier events race with backend PLAYER_INFO.
    // Unauthenticated: hide others until /login, but keep self so we do not wipe
    // SkinsRestorer textures with a bare clearAll + re-add.
    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        if (!authRepo.isAuthenticated(event.player.username)) {
            val selfId = event.player.uniqueId
            event.player.tabList.entries.toList().forEach { entry ->
                if (entry.profile.id != selfId) {
                    event.player.tabList.removeEntry(entry.profile.id)
                }
            }
            return
        }
        scheduleRebuilds()
    }

    /** Bounded retries — not a permanent poll. Covers late currentServer / auth / skins. */
    fun scheduleRebuilds() {
        for (delayMs in REBUILD_DELAYS_MS) {
            proxy.scheduler
                .buildTask(plugin, java.lang.Runnable { rebuilder.rebuildGlobalTabList() })
                .delay(delayMs, TimeUnit.MILLISECONDS)
                .schedule()
        }
    }

    companion object {
        // Include a late pass so SkinsRestorer can finish Mojang apply before we
        // freeze a bare GameProfile into the tab list.
        private val REBUILD_DELAYS_MS = longArrayOf(0L, 250L, 1000L, 2500L, 5000L)
    }
}
