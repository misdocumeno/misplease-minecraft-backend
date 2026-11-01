package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.TeleportHandler
import io.github.misdocumeno.domain.model.TeleportRequest
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import kotlin.uuid.toKotlinUuid

class PaperEventListener(
    private val pending: MutableMap<String, TeleportRequest>,
    private val handler: TeleportHandler,
) : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val request = pending[event.player.name] ?: return
        pending.remove(event.player.name)
        handler.handle(request)
    }
}