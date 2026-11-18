package io.github.misdocumeno.command

import io.github.misdocumeno.OpenInvPlugin
import io.github.misdocumeno.domain.InventoryKind
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class OpenInvCommand(
    private val plugin: OpenInvPlugin,
    private val kind: InventoryKind,
) : CommandExecutor, TabCompleter {

    private val usageKey = if (kind == InventoryKind.INVENTORY) "usage-inventory" else "usage-ender"
    private val permission = if (kind == InventoryKind.INVENTORY) "misplease.openinv" else "misplease.openender"

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (kind == InventoryKind.INVENTORY && args.size == 1 && args[0].equals("reload", ignoreCase = true)) {
            if (!sender.hasPermission("misplease.openinv.admin")) {
                sender.sendMessage(plugin.messages.component("no-permission"))
                return true
            }
            plugin.reloadAllConfig()
            sender.sendMessage(plugin.messages.component("reloaded"))
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(plugin.messages.component("player-only"))
            return true
        }
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(plugin.messages.component("no-permission"))
            return true
        }
        if (args.isEmpty() || args.size > 2) {
            sender.sendMessage(plugin.messages.component(usageKey))
            return true
        }

        val token = args[0]
        if (token.equals("reload", ignoreCase = true)) {
            sender.sendMessage(plugin.messages.component(usageKey))
            return true
        }
        val (explicitGroup, server) = parseGroupAndServer(args.getOrNull(1))

        val local = Bukkit.getPlayerExact(token)
        if (local != null) {
            if (local.uniqueId == sender.uniqueId) {
                sender.sendMessage(plugin.messages.component("self-target"))
                return true
            }
            if (server != plugin.serverId) {
                sender.sendMessage(plugin.messages.component("other-server", "player" to local.name, "server" to server))
                return true
            }
            plugin.openSession(sender, local.uniqueId, local.name, kind, explicitGroup, server)
            return true
        }

        // Offline / other backend: resolve without blocking the server thread.
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val resolved = resolveOffline(token)
            val lastSession = resolved?.let { plugin.lastSessions.lookup(it.first) }
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!sender.isOnline) return@Runnable
                when {
                    resolved == null ->
                        sender.sendMessage(plugin.messages.component("unknown-player", "player" to token))

                    server != plugin.serverId ->
                        sender.sendMessage(
                            plugin.messages.component("other-server", "player" to resolved.second, "server" to server),
                        )

                    lastSession != null && lastSession.server != plugin.serverId ->
                        sender.sendMessage(
                            plugin.messages.component(
                                "other-server",
                                "player" to resolved.second,
                                "server" to lastSession.server,
                            ),
                        )

                    else -> plugin.openSession(sender, resolved.first, resolved.second, kind, explicitGroup, server)
                }
            })
        })
        return true
    }

    private fun resolveOffline(name: String): Pair<java.util.UUID, String>? {
        plugin.mvi.findByName(name)?.let { return it }
        val offline = runCatching { Bukkit.getOfflinePlayer(name) }.getOrNull() ?: return null
        if (!offline.hasPlayedBefore()) return null
        return offline.uniqueId to (offline.name ?: name)
    }

    private fun parseGroupAndServer(raw: String?): Pair<String?, String> {
        if (raw.isNullOrBlank()) return null to plugin.serverId
        val at = raw.indexOf('@')
        if (at < 0) return raw to plugin.serverId
        val group = raw.substring(0, at).takeIf { it.isNotBlank() }
        val server = raw.substring(at + 1).ifBlank { plugin.serverId }
        return group to server
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> {
        if (sender !is Player) return emptyList()
        return when (args.size) {
            1 -> {
                val partial = args[0]
                val names = buildSet {
                    Bukkit.getOnlinePlayers().forEach { add(it.name) }
                    plugin.mvi.names().forEach { add(it) }
                    if (kind == InventoryKind.INVENTORY && "reload".startsWith(partial, ignoreCase = true)) {
                        add("reload")
                    }
                }
                names.filter { it.startsWith(partial, ignoreCase = true) && !it.equals(sender.name, ignoreCase = true) }
                    .sorted()
            }

            2 -> {
                val partial = args[1]
                val groups = plugin.mvi.groups()
                if (partial.contains('@')) {
                    val group = partial.substringBefore('@')
                    listOf("$group@${plugin.serverId}")
                } else {
                    groups.filter { it.startsWith(partial, ignoreCase = true) }
                }
            }

            else -> emptyList()
        }
    }
}
