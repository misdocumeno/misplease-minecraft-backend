package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.TeleportAskHandler
import io.github.misdocumeno.domain.model.TeleportAskNotification
import io.github.misdocumeno.domain.model.TeleportAskType
import org.bukkit.Bukkit

class PaperTeleportAskHandler(
    private val messages: TeleportMessages,
) : TeleportAskHandler {

    override fun handle(notification: TeleportAskNotification) {
        val player = Bukkit.getPlayer(notification.to) ?: return
        messages.tpaIncoming(
            player,
            notification.from,
            here = notification.type == TeleportAskType.TPA_HERE,
        )
    }
}
