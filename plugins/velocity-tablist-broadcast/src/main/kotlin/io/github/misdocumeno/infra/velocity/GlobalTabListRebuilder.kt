package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.player.TabListEntry
import io.lettuce.core.api.StatefulRedisConnection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GlobalTabListRebuilder(
    private val proxy: ProxyServer,
    private val connection: StatefulRedisConnection<String, String>,
) {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()
    private val mm = MiniMessage.miniMessage()

    /**
     * Display names published by the backends (nickname + LuckPerms colours), keyed
     * by UUID. The proxy cannot read a backend `Player`, so this cache is the only
     * way the tab list sees `/nickname`; the fallback is the LP prefix + username.
     */
    private val names = ConcurrentHashMap<String, String>()

    fun updateName(uuid: String, name: String?) {
        if (name == null) names.remove(uuid) else names[uuid] = name
    }

    fun rebuildGlobalTabList() {
        connection.async().smembers(AUTH_SET).whenComplete { authenticated, error ->
            if (error != null) {
                return@whenComplete
            }
            val authed = proxy.allPlayers.filter { it.username in authenticated.orEmpty() }
            loadNames(authed) { rebuildWith(authed) }
        }
    }

    /**
     * On proxy restart the pub/sub cache is empty while backends keep running, so
     * the first rebuild backfills from their Redis keys. MGET is one round trip.
     */
    private fun loadNames(authed: List<Player>, then: () -> Unit) {
        val missing = authed.filter { !names.containsKey(it.uniqueId.toString()) }
        if (missing.isEmpty()) {
            then()
            return
        }
        connection.async().mget(*missing.map { key(it.uniqueId) }.toTypedArray()).whenComplete { values, error ->
            if (error == null) {
                // mget returns KeyValue pairs (key + nullable value) in this Lettuce version.
                val found = values.orEmpty().associate { it.key to it.value }
                missing.forEach { player ->
                    val value = found[key(player.uniqueId)]
                    if (!value.isNullOrBlank()) names[player.uniqueId.toString()] = value
                }
            }
            then()
        }
    }

    private fun rebuildWith(authed: List<Player>) {
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
        // Backend-published name first (same string chat renders), LP fallback until
        // the player's backend has replied.
        names[player.uniqueId.toString()]?.let { stored ->
            runCatching { mm.deserialize(stored) }.getOrNull()?.let { return it }
        }
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
        private const val NAME_KEY_PREFIX = "misplease:tablist:name:"

        private fun key(uuid: UUID): String = "$NAME_KEY_PREFIX$uuid"
    }
}
