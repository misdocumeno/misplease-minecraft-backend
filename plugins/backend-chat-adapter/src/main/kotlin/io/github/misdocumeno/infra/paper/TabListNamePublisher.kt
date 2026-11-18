package io.github.misdocumeno.infra.paper

import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class TabListNameUpdate(val uuid: String, val name: String? = null)

/**
 * Publishes the same display name global chat uses (LuckPerms prefix/suffix +
 * `player.displayName()`) so the proxy can colour the tab list with nicknames.
 *
 * The proxy cannot read a backend `Player`, so the name travels over Redis: one
 * key per player (`misplease:tablist:name:<uuid>`, with a TTL so a crashed backend
 * does not leave a name forever) plus a pub/sub ping so an open proxy updates at
 * once. A one-second compare-and-publish loop catches `/nickname` and any other
 * plugin that changes the display name; it only writes when the name changed.
 */
class TabListNamePublisher(
    private val plugin: Plugin,
    private val connection: StatefulRedisConnection<String, String>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Listener {

    private val last = ConcurrentHashMap<UUID, String>()

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { plugin.server.onlinePlayers.forEach(::publish) },
            REFRESH_TICKS,
            REFRESH_TICKS,
        )
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        // LuckPerms may still be resolving metadata during the join event.
        plugin.server.scheduler.runTask(plugin, Runnable { publish(event.player) })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        last.remove(uuid)
        connection.async().del(key(uuid))
        publishUpdate(TabListNameUpdate(uuid.toString(), null))
    }

    private fun publish(player: Player) {
        if (!player.isOnline) return
        val name = runCatching { LuckPermsNameFormat.miniMessageName(player) }.getOrNull() ?: return
        val changed = last.put(player.uniqueId, name) != name
        // Keep the TTL alive while the player is online; the ping is only on change.
        connection.async().psetex(key(player.uniqueId), TTL_MILLIS, name)
        if (changed) publishUpdate(TabListNameUpdate(player.uniqueId.toString(), name))
    }

    private fun publishUpdate(update: TabListNameUpdate) {
        connection.async().publish(CHANNEL, json.encodeToString(update))
    }

    companion object {
        const val CHANNEL = "misplease:tablist:name"

        private const val KEY_PREFIX = "misplease:tablist:name:"
        private const val TTL_MILLIS = 3_600_000L
        private const val REFRESH_TICKS = 40L

        fun key(uuid: UUID): String = "$KEY_PREFIX$uuid"
    }
}
