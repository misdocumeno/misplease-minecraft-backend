package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.infra.redis.RedisAuthSet
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class ReplyCommand(
    private val proxy: ProxyServer,
    private val auth: RedisAuthSet,
    private val replies: ReplyIndex,
) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        if (source !is Player) {
            source.sendMessage(Component.text("Players only.", NamedTextColor.GRAY))
            return
        }
        if (!auth.isAuthenticated(source.username)) {
            source.sendMessage(Component.text("Log in first.", NamedTextColor.GRAY))
            return
        }
        val args = invocation.arguments()
        if (args.isEmpty()) {
            source.sendMessage(Component.text("Usage: /r <message>", NamedTextColor.GRAY))
            return
        }
        val partnerId = replies.partnerOf(source.uniqueId)
        if (partnerId == null) {
            source.sendMessage(Component.text("No one to reply to.", NamedTextColor.GRAY))
            return
        }
        val target = proxy.getPlayer(partnerId).orElse(null)
        if (target == null || !auth.isAuthenticated(target.username)) {
            source.sendMessage(Component.text("That player is no longer online.", NamedTextColor.GRAY))
            return
        }
        val body = args.joinToString(" ")
        MsgCommand.deliver(source, target, body)
        replies.remember(source.uniqueId, target.uniqueId)
    }
}
