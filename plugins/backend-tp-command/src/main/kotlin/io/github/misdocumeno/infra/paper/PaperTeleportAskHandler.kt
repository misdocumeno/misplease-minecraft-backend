package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.TeleportAskHandler
import io.github.misdocumeno.domain.model.TeleportAskNotification
import io.github.misdocumeno.domain.model.TeleportAskType
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

class PaperTeleportAskHandler(
    private val plugin: Plugin,
    private val messages: TeleportMessages,
) : TeleportAskHandler {

    override fun handle(notification: TeleportAskNotification) {
        // Pub/sub thread — sendMessage must be on the main thread.
        plugin.runSync {
            val player = Bukkit.getPlayer(notification.to) ?: return@runSync
            messages.tpaIncoming(
                player,
                notification.from,
                here = notification.type == TeleportAskType.TPA_HERE,
            )
        }
    }
}
