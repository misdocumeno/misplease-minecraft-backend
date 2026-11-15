package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.player.TabListEntry
import com.velocitypowered.api.util.GameProfile
import io.lettuce.core.api.StatefulRedisConnection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPermsProvider
import net.skinsrestorer.api.SkinsRestorerProvider
import net.skinsrestorer.api.property.SkinProperty
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GlobalTabListRebuilder(
    private val proxy: ProxyServer,
    private val connection: StatefulRedisConnection<String, String>,
) {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()
    private val textureCache = ConcurrentHashMap<UUID, GameProfile.Property>()
    private val loggedSrMiss = ConcurrentHashMap.newKeySet<UUID>()

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
        val onlineIds = authed.map { it.uniqueId }.toSet()
        textureCache.keys.retainAll(onlineIds)
        loggedSrMiss.retainAll(onlineIds)

        authed.forEach { viewer ->
            // Skip until Velocity has bound a backend — caller retries shortly after.
            if (serverName(viewer) == null) return@forEach

            val wanted = onlineIds

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
        val profile = profileWithTextures(target)
        val existing = viewer.tabList.getEntry(target.uniqueId)
        if (existing.isPresent) {
            val entry = existing.get()
            val needsTextureUpgrade = !hasTextures(entry.profile) && hasTextures(profile)
            if (!needsTextureUpgrade) {
                entry.setDisplayName(displayName)
                entry.setLatency(latency)
                return
            }
            // Bare profile was added before textures settled. TabListEntry cannot swap
            // GameProfile in place — remove and re-add.
            viewer.tabList.removeEntry(target.uniqueId)
        } else if (!hasTextures(profile)) {
            // Do not stamp Steve heads; a later rebuild / backend PLAYER_INFO may win.
            return
        }

        viewer.tabList.addEntry(
            TabListEntry.builder()
                .tabList(viewer.tabList)
                .profile(profile)
                .displayName(displayName)
                .latency(latency)
                .listed(true)
                .showHat(true)
                .build(),
        )
    }

    /**
     * Prefer a profile that already carries skin textures so we do not stamp Steve
     * over a SkinsRestorer / backend-applied entry on a later rebuild.
     */
    private fun profileWithTextures(target: Player): GameProfile {
        texturesProperty(target)?.let { prop ->
            return GameProfile(target.uniqueId, target.username, listOf(prop))
        }
        return target.gameProfile
    }

    private fun texturesProperty(target: Player): GameProfile.Property? {
        // SR applies via setGameProfileProperties — that list can diverge from gameProfile.properties.
        target.gameProfileProperties.firstOrNull { isTextureProp(it) }?.let { return it }
        target.gameProfile.properties.firstOrNull { isTextureProp(it) }?.let { return it }

        for (viewer in proxy.allPlayers) {
            val entry = viewer.tabList.getEntry(target.uniqueId)
            if (!entry.isPresent) continue
            entry.get().profile.properties.firstOrNull { isTextureProp(it) }?.let { return it }
        }

        return texturesFromSkinsRestorer(target)
    }

    private fun isTextureProp(prop: GameProfile.Property): Boolean =
        prop.name == SkinProperty.TEXTURES_NAME || prop.name == "textures"

    private fun texturesFromSkinsRestorer(target: Player): GameProfile.Property? {
        textureCache[target.uniqueId]?.let { return it }
        return runCatching {
            val storage = SkinsRestorerProvider.get().playerStorage
            val property = storage.getSkinForPlayer(target.uniqueId, target.username, false)
                .or { storage.getSkinOfPlayer(target.uniqueId) }
                .orElse(null)
            if (property == null) {
                if (loggedSrMiss.add(target.uniqueId)) {
                    log.warn("tablist: no SR skin for {} ({})", target.username, target.uniqueId)
                }
                return@runCatching null
            }
            GameProfile.Property(
                SkinProperty.TEXTURES_NAME,
                property.value,
                property.signature,
            )
        }.onFailure { e ->
            if (loggedSrMiss.add(target.uniqueId)) {
                log.warn("tablist: SR skin lookup failed for {}: {}", target.username, e.message)
            }
        }.getOrNull()?.also { textureCache[target.uniqueId] = it }
    }

    private fun hasTextures(profile: GameProfile): Boolean =
        profile.properties.any { isTextureProp(it) }

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
        private val log = LoggerFactory.getLogger(GlobalTabListRebuilder::class.java)
    }
}
