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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GlobalTabListRebuilder(
    private val proxy: ProxyServer,
    private val connection: StatefulRedisConnection<String, String>,
) {
    private val legacy = LegacyComponentSerializer.legacyAmpersand()
    private val textureCache = ConcurrentHashMap<UUID, GameProfile.Property>()

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
                .build(),
        )
    }

    /**
     * Prefer a profile that already carries skin textures so we do not stamp Steve
     * over a SkinsRestorer / backend-applied entry on a later rebuild.
     */
    private fun profileWithTextures(target: Player): GameProfile {
        if (hasTextures(target.gameProfile)) {
            return target.gameProfile
        }
        for (viewer in proxy.allPlayers) {
            val entry = viewer.tabList.getEntry(target.uniqueId)
            if (entry.isPresent && hasTextures(entry.get().profile)) {
                return entry.get().profile
            }
        }
        val fromSr = texturesFromSkinsRestorer(target) ?: return target.gameProfile
        return target.gameProfile.addProperty(fromSr)
    }

    private fun texturesFromSkinsRestorer(target: Player): GameProfile.Property? {
        textureCache[target.uniqueId]?.let { return it }
        return runCatching {
            val property = SkinsRestorerProvider.get()
                .playerStorage
                .getSkinForPlayer(target.uniqueId, target.username)
                .orElse(null)
                ?: return@runCatching null
            GameProfile.Property(
                SkinProperty.TEXTURES_NAME,
                property.value,
                property.signature,
            )
        }.getOrNull()?.also { textureCache[target.uniqueId] = it }
    }

    private fun hasTextures(profile: GameProfile): Boolean =
        profile.properties.any { it.name == SkinProperty.TEXTURES_NAME || it.name == "textures" }

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
