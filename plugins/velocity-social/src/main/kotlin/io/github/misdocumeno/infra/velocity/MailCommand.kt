package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.infra.database.MailRepository
import io.github.misdocumeno.infra.redis.RedisAuthSet
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MailCommand(
    private val proxy: ProxyServer,
    private val auth: RedisAuthSet,
    private val mail: MailRepository,
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
            usage(source)
            return
        }
        when (args[0].lowercase()) {
            "send" -> send(source, args.drop(1))
            "read" -> read(source)
            "clear" -> clear(source, args.drop(1))
            "sendall" -> sendAll(source, args.drop(1))
            else -> usage(source)
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val source = invocation.source() as? Player ?: return emptyList()
        if (!auth.isAuthenticated(source.username)) return emptyList()
        val args = invocation.arguments()
        return when (args.size) {
            0, 1 -> listOf("send", "read", "clear", "sendall")
                .filter { it.startsWith(args.firstOrNull()?.lowercase().orEmpty()) }
            2 -> when (args[0].lowercase()) {
                "send" -> auth.authenticatedNames()
                    .filter { it != source.username && it.lowercase().startsWith(args[1].lowercase()) }
                    .sorted()
                    .take(50)
                "clear" -> listOf("all") + mail.list(source.username).map { it.id.toString() }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun send(from: Player, args: List<String>) {
        if (args.size < 2) {
            from.sendMessage(Component.text("Usage: /mail send <player> <message>", NamedTextColor.GRAY))
            return
        }
        val target = args[0]
        val body = args.drop(1).joinToString(" ")
        if (target.equals(from.username, ignoreCase = true)) {
            from.sendMessage(Component.text("You cannot mail yourself.", NamedTextColor.GRAY))
            return
        }
        mail.send(target, from.username, body)
        from.sendMessage(
            Component.text("Mail sent to ", NamedTextColor.GRAY)
                .append(Component.text(target, NamedTextColor.WHITE))
                .append(Component.text(".", NamedTextColor.GRAY)),
        )
        proxy.getPlayer(target).ifPresent { online ->
            if (auth.isAuthenticated(online.username)) {
                online.sendMessage(
                    Component.text("You have new mail. Use ", NamedTextColor.YELLOW)
                        .append(Component.text("/mail read", NamedTextColor.WHITE))
                        .append(Component.text(".", NamedTextColor.YELLOW)),
                )
            }
        }
    }

    private fun read(player: Player) {
        val rows = mail.list(player.username)
        if (rows.isEmpty()) {
            player.sendMessage(Component.text("You have no mail.", NamedTextColor.GRAY))
            return
        }
        player.sendMessage(Component.text("Mail (${rows.size}):", NamedTextColor.GRAY))
        for (row in rows) {
            val stamp = FORMAT.format(Instant.ofEpochMilli(row.createdAt))
            val unread = if (row.readAt == null) "*" else " "
            player.sendMessage(
                Component.text("$unread#${row.id} ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(row.senderName, NamedTextColor.AQUA))
                    .append(Component.text(" [$stamp]: ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(row.body, NamedTextColor.WHITE)),
            )
        }
        mail.markAllRead(player.username)
    }

    private fun clear(player: Player, args: List<String>) {
        if (args.isEmpty() || args[0].equals("all", ignoreCase = true)) {
            val n = mail.clearAll(player.username)
            player.sendMessage(Component.text("Cleared $n mail(s).", NamedTextColor.GRAY))
            return
        }
        val id = args[0].toLongOrNull()
        if (id == null) {
            player.sendMessage(Component.text("Usage: /mail clear [all|<id>]", NamedTextColor.GRAY))
            return
        }
        if (mail.clearOne(player.username, id)) {
            player.sendMessage(Component.text("Cleared mail #$id.", NamedTextColor.GRAY))
        } else {
            player.sendMessage(Component.text("Mail #$id not found.", NamedTextColor.GRAY))
        }
    }

    private fun sendAll(from: Player, args: List<String>) {
        if (!from.hasPermission("misplease.mail.sendall")) {
            from.sendMessage(Component.text("No permission.", NamedTextColor.GRAY))
            return
        }
        if (args.isEmpty()) {
            from.sendMessage(Component.text("Usage: /mail sendall <message>", NamedTextColor.GRAY))
            return
        }
        val body = args.joinToString(" ")
        var n = 0
        for (name in auth.authenticatedNames()) {
            if (name.equals(from.username, ignoreCase = true)) continue
            mail.send(name, from.username, body)
            n++
        }
        from.sendMessage(Component.text("Mail sent to $n player(s).", NamedTextColor.GRAY))
    }

    private fun usage(player: Player) {
        player.sendMessage(Component.text("Usage: /mail <send|read|clear|sendall> …", NamedTextColor.GRAY))
    }

    companion object {
        private val FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.of("America/Argentina/Buenos_Aires"))
    }
}
