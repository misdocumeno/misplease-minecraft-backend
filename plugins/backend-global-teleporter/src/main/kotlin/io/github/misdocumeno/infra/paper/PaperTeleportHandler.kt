package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.TeleportHandler
import io.github.misdocumeno.domain.model.TeleportRequest
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.concurrent.ConcurrentMap

class PaperTeleportHandler(
    private val plugin: Plugin,
    private val serverId: String,
    private val pending: ConcurrentMap<String, PendingTeleport>,
) : TeleportHandler {

    private val mm = MiniMessage.miniMessage()

    override fun handle(event: TeleportRequest) {
        if (serverId != event.location.server) {
            return
        }

        // Pub/sub arrives off the main thread; Bukkit world/player access must not.
        val run = Runnable { handleOnMain(event) }
        if (Bukkit.isPrimaryThread()) {
            run.run()
        } else {
            Bukkit.getScheduler().runTask(plugin, run)
        }
    }

    private fun handleOnMain(event: TeleportRequest) {
        val world = Bukkit.getWorld(event.location.world) ?: return plugin.logger.severe(
            "[GlobalTeleported] Teleport requested for player ${event.username} " +
                    "to world '${event.location.world}' but it doesn't exist."
        )

        val player = Bukkit.getPlayer(event.username)

        if (player == null) {
            // Cross-server: the proxy may be holding this connect until the
            // backend wakes. Expire instead of applying stale coordinates on a
            // much later join.
            pending.entries.removeIf { it.value.isExpired() }
            pending[event.username] = PendingTeleport(event)
            return
        }

        player.teleportAsync(
            Location(
                world,
                event.location.x,
                event.location.y,
                event.location.z,
                event.location.yaw,
                event.location.pitch,
            )
        ).thenRun {
            if (event.feedback == null) return@thenRun
            Bukkit.getScheduler().runTask(plugin) { ->
                val online = Bukkit.getPlayer(event.username) ?: return@runTask
                val feedback = event.feedback
                feedback.chat?.let { online.sendMessage(mm.deserialize(it)) }
                feedback.title?.let {
                    val title = mm.deserialize(it.title)
                    val subtitle = it.subtitle?.let { sub -> mm.deserialize(sub) }
                    online.showTitle(
                        Title.title(
                            title,
                            subtitle ?: Component.empty(),
                            Title.Times.times(
                                Duration.ofMillis(it.fadeIn),
                                Duration.ofMillis(it.stay),
                                Duration.ofMillis(it.fadeOut),
                            )
                        )
                    )
                }
                feedback.sound?.let { soundFeedback ->
                    runCatching { Sound.valueOf(soundFeedback.sound) }.getOrNull()?.let { sound ->
                        online.playSound(online.location, sound, soundFeedback.volume, soundFeedback.pitch)
                    }
                }
            }
        }
    }
}