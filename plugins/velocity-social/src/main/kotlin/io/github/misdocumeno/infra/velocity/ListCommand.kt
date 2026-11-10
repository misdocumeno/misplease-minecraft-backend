package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.infra.redis.RedisAuthSet
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class ListCommand(
    private val proxy: ProxyServer,
    private val auth: RedisAuthSet,
) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        if (source is Player && !auth.isAuthenticated(source.username)) {
            source.sendMessage(Component.text("Log in first.", NamedTextColor.GRAY))
            return
        }

        val byServer = linkedMapOf<String, MutableList<Player>>()
        for (player in proxy.allPlayers) {
            if (!auth.isAuthenticated(player.username)) continue
            val server = player.currentServer.orElse(null)?.serverInfo?.name ?: "…"
            byServer.getOrPut(server) { mutableListOf() }.add(player)
        }
        val total = byServer.values.sumOf { it.size }
        source.sendMessage(
            Component.text("Online ", NamedTextColor.GRAY)
                .append(Component.text("($total)", NamedTextColor.WHITE))
                .append(Component.text(":", NamedTextColor.GRAY)),
        )
        if (total == 0) {
            source.sendMessage(Component.text("  (nobody)", NamedTextColor.DARK_GRAY))
            return
        }
        for ((server, players) in byServer.toSortedMap()) {
            var line: Component = Component.text("  $server: ", NamedTextColor.DARK_PURPLE)
            players.sortedBy { it.username.lowercase() }.forEachIndexed { i, p ->
                if (i > 0) line = line.append(Component.text(", ", NamedTextColor.DARK_GRAY))
                line = line.append(NameFormat.colored(p))
            }
            source.sendMessage(line)
        }
    }
}