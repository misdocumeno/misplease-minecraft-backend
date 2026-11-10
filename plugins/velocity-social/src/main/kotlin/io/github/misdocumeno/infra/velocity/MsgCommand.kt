package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.infra.redis.RedisAuthSet
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

class MsgCommand(
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
        if (args.size < 2) {
            source.sendMessage(Component.text("Usage: /msg <player> <message>", NamedTextColor.GRAY))
            return
        }
        val targetName = args[0]
        val target = proxy.getPlayer(targetName).orElse(null)
        if (target == null || !auth.isAuthenticated(target.username)) {
            source.sendMessage(
                Component.text("Player ", NamedTextColor.GRAY)
                    .append(Component.text(targetName, NamedTextColor.WHITE))
                    .append(Component.text(" was not found.", NamedTextColor.GRAY)),
            )
            return
        }
        if (target.uniqueId == source.uniqueId) {
            source.sendMessage(Component.text("You cannot message yourself.", NamedTextColor.GRAY))
            return
        }
        val body = args.drop(1).joinToString(" ")
        deliver(source, target, body)
        replies.remember(source.uniqueId, target.uniqueId)
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val source = invocation.source() as? Player ?: return emptyList()
        if (!auth.isAuthenticated(source.username)) return emptyList()
        val args = invocation.arguments()
        if (args.size > 1) return emptyList()
        val partial = args.firstOrNull()?.lowercase().orEmpty()
        return auth.authenticatedNames()
            .filter { it != source.username && it.lowercase().startsWith(partial) }
            .sorted()
            .take(50)
    }

    companion object {
        fun deliver(from: Player, to: Player, body: String) {
            val outgoing = Component.text()
                .append(Component.text("To ", NamedTextColor.GRAY))
                .append(NameFormat.colored(to))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(body, NamedTextColor.WHITE))
                .build()
            val incoming = Component.text()
                .append(Component.text("From ", NamedTextColor.GRAY))
                .append(NameFormat.colored(from))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(body, NamedTextColor.WHITE))
                .build()
            from.sendMessage(outgoing)
            to.sendMessage(incoming)
            to.sendActionBar(
                Component.text("New message from ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(from.username, NamedTextColor.GRAY, TextDecoration.ITALIC)),
            )
        }
    }
}
