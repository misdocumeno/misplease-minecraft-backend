package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.player.TabListEntry
import io.github.misdocumeno.domain.AuthenticatedRepository
import net.kyori.adventure.text.Component

class GlobalTabListRebuilder(
    private val proxy: ProxyServer,
    private val authRepo: AuthenticatedRepository,
) {

    fun rebuildGlobalTabList() {
        val authedPlayers = proxy.allPlayers.filter {
            authRepo.isAuthenticated(it.username)
        }

        authedPlayers.forEach { viewer ->
            viewer.tabList.clearAll()

            authedPlayers.forEach { target ->
                viewer.tabList.addEntry(
                    TabListEntry.builder()
                        .tabList(viewer.tabList)
                        .profile(target.gameProfile)
                        .displayName(Component.text(target.username))
                        .latency(target.ping.toInt())
                        .build()
                )
            }
        }
    }
}
