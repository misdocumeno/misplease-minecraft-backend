package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.infra.paper.TeleportMessages
import io.github.misdocumeno.infra.paper.runSync
import io.github.misdocumeno.infra.redis.TpaRequestQueries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class TeleportCancelCommand(
    private val plugin: Plugin,
    private val scope: CoroutineScope,
    private val usernames: UsernameRepository,
    private val tpa: TpaRequestQueries,
    private val messages: TeleportMessages,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            messages.playerOnly(sender)
            return true
        }
        if (args.size > 1) return false

        val requester = sender.name
        val arg = args.firstOrNull()
        scope.launch {
            val requests = tpa.outgoing(requester)
            plugin.runSync {
                if (!sender.isOnline) return@runSync
                when {
                    requests.isEmpty() -> messages.noRequestsCancel(sender)
                    arg == null && requests.size > 1 -> messages.specifyCancel(sender)
                    arg == null -> {
                        val (target) = requests.first()
                        scope.launch { tpa.clear(requester, target) }
                        messages.cancelled(sender, target)
                    }
                    arg == "*" -> {
                        messages.cancelledAll(sender)
                        scope.launch { requests.forEach { (target) -> tpa.clear(requester, target) } }
                    }
                    else -> {
                        val target =
                            usernames.findCaseInsensitive(arg) ?: usernames.findPartial(arg) ?: arg
                        if (requests.none { (name) -> name.equals(target, ignoreCase = true) }) {
                            messages.noRequestTo(sender, target)
                        } else {
                            scope.launch { tpa.clear(requester, target) }
                            messages.cancelled(sender, target)
                        }
                    }
                }
            }
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>,
    ): List<String> {
        if (sender !is Player || args.size != 1) return emptyList()
        val partial = args.first()
        val pending = tpa.suggestOutgoing(sender.name, partial)
        return if ("*".startsWith(partial)) pending + "*" else pending
    }
}
