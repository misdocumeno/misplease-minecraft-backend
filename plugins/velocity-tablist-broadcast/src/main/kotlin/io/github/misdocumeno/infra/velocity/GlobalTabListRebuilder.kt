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
        val wanted = authed.map { it.uniqueId }.toSet()

        authed.forEach { viewer ->
            // Skip until Velocity has bound a backend — caller retries shortly after.
            if (serverName(viewer) == null) return@forEach

            viewer.tabList.entries.toList().forEach { entry ->
                if (entry.profile.id !in wanted) {
                    viewer.tabList.removeEntry(entry.profile.id)
                }
            }

            // Upsert locals AND remotes. After we strip remotes pre-/login, the backend
            // does not re-send PLAYER_INFO for players already online — tech has no
            // TabList plugin to refill, so proxy must own the whole list.
            authed.forEach { target ->
                upsert(viewer, target)
            }
        }
    }

    private fun upsert(viewer: Player, target: Player) {
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

    /*
     * Tab heads: tried filling TabListEntry GameProfiles with textures from
     * Player.gameProfileProperties, other viewers' entries, and SkinsRestorer
     * (getSkinForPlayer / getSkinOfPlayer), then re-adding entries when textures
     * arrived (incl. showHat/listed). Body skins worked; tab heads never showed
     * on this offline Velocity setup (and reportedly never did even pre-proxy).
     * Left disabled — not worth Crypt4Offline / encryption side quests for this.
     *
     * private fun profileWithTextures(target: Player): GameProfile { ... }
     * private fun texturesProperty(target: Player): GameProfile.Property? { ... }
     * private fun texturesFromSkinsRestorer(target: Player): GameProfile.Property? { ... }
     * private fun hasTextures(profile: GameProfile): Boolean { ... }
     */

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
