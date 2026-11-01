package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.TeleportAskHandler
import io.github.misdocumeno.domain.model.TeleportAskNotification
import io.github.misdocumeno.domain.model.TeleportAskType
import org.bukkit.Bukkit

class PaperTeleportAskHandler : TeleportAskHandler {

    override fun handle(notification: TeleportAskNotification) {
        Bukkit.getPlayer(notification.to)?.sendMessage(
            when (notification.type) {
                TeleportAskType.TPA -> "${notification.from} wants to teleport to you. Accept with /tpaccept."
                TeleportAskType.TPA_HERE -> "${notification.from} wants you to teleport to them. Accept with /tpaccept."
            }
        )
    }
}