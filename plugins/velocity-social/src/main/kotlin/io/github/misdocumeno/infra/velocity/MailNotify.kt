package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.infra.database.MailRepository
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class MailNotify(
    private val proxy: ProxyServer,
    private val mail: MailRepository,
) {
    fun onAuthenticated(username: String) {
        val unread = mail.unreadCount(username)
        if (unread <= 0) return
        proxy.getPlayer(username).ifPresent { player ->
            player.sendMessage(
                Component.text("You have ", NamedTextColor.YELLOW)
                    .append(Component.text("$unread", NamedTextColor.WHITE))
                    .append(Component.text(" unread mail. /mail read", NamedTextColor.YELLOW)),
            )
        }
    }
}
