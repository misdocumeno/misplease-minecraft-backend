package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.player.TabListEntry
import io.lettuce.core.api.StatefulRedisConnection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider

class GlobalTabListRebuilder(
    private val proxy: ProxyServer,
    private val connection: StatefulRedisConnection<String, String>,
) {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun rebuildGlobalTabList() {
        connection.async().smembers(AUTH_SET).whenComplete { names, error ->
            if (error != null) {
                return@whenComplete
            }
            rebuildWith(names.orEmpty())
        }
    }

    private fun rebuildWith(authenticatedNames: Set<String>) {
        val authed = proxy.allPlayers.filter { it.username in authenticatedNames }

        authed.forEach { viewer ->
            val here = serverName(viewer)
            val onThisServer = proxy.allPlayers
                .filter { here != null && serverName(it) == here }
                .map { it.uniqueId }
                .toSet()
            val remote = authed.filter { it.uniqueId !in onThisServer }
            val wanted = onThisServer + remote.map { it.uniqueId }

            viewer.tabList.entries.toList().forEach { entry ->
                if (entry.profile.id !in wanted) {
                    viewer.tabList.removeEntry(entry.profile.id)
                }
            }

            remote.forEach { target ->
                upsertRemote(viewer, target)
            }
        }
    }

    private fun upsertRemote(viewer: Player, target: Player) {
        // Local players keep TabList plugin formatting on main; remotes get LP prefix color.
        val displayName = coloredName(target)
        val latency = target.ping.toInt().coerceAtLeast(0)
        val existing = viewer.tabList.getEntry(target.uniqueId)
        if (existing.isPresent) {
            existing.get().apply {
                setDisplayName(displayName)
                setLatency(latency)
            }
            return
        }

        viewer.tabList.addEntry(
            TabListEntry.builder()
                .tabList(viewer.tabList)
                .profile(target.gameProfile)
                .displayName(displayName)
                .latency(latency)
                .build(),
        )
    }

    private fun coloredName(player: Player): Component {
        val prefix = runCatching {
            LuckPermsProvider.get()
                .getPlayerAdapter(Player::class.java)
                .getMetaData(player)
                .prefix
        }.getOrNull().orEmpty()
        return legacy.deserialize(prefix + player.username)
    }

    private fun serverName(player: Player): String? =
        player.currentServer.orElse(null)?.serverInfo?.name

    companion object {
        private const val AUTH_SET = "game-sessions:authenticated"
    }
}
