package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.TeleportHandler
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PaperEventListener(
    private val pending: MutableMap<String, PendingTeleport>,
    private val handler: TeleportHandler,
) : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val entry = pending.remove(event.player.name) ?: return
        if (entry.isExpired()) {
            // The wake/hold took longer than the TTL, or the player joined for
            // another reason. Do not teleport them to an old destination.
            return
        }
        handler.handle(entry.request)
    }
}
