package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.domain.model.TeleportAskType
import io.github.misdocumeno.infra.paper.TeleportMessages
import io.github.misdocumeno.infra.paper.runSync
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class TeleportDenyCommand(
    private val plugin: Plugin,
    private val scope: CoroutineScope,
    private val usernames: UsernameRepository,
    private val redis: RedisCommands<String, String>,
    private val messages: TeleportMessages,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            messages.playerOnly(sender)
            return true
        }
        if (args.size > 1) return false

        val target = sender.name
        val arg = args.firstOrNull()
        scope.launch {
            val requests = getPendingRequests(target)
            plugin.runSync {
                if (!sender.isOnline) return@runSync
                when {
                    requests.isEmpty() -> messages.noRequestsDeny(sender)
                    arg == null && requests.size > 1 -> messages.specifyDeny(sender)
                    arg == null -> {
                        val (requester) = requests.first()
                        scope.launch { clearRequest(requester, target) }
                        messages.denied(sender, requester)
                    }
                    arg == "*" -> {
                        messages.deniedAll(sender)
                        scope.launch { requests.forEach { (requester) -> clearRequest(requester, target) } }
                    }
                    else -> {
                        val requester =
                            usernames.findCaseInsensitive(arg) ?: usernames.findPartial(arg) ?: arg
                        if (requests.none { (name) -> name == requester }) {
                            messages.noRequestFrom(sender, requester)
                        } else {
                            scope.launch { clearRequest(requester, target) }
                            messages.denied(sender, requester)
                        }
                    }
                }
            }
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>,
    ): List<String>? {
        // Rare path; sync Redis on tab is acceptable vs empty completions.
        return if (sender is Player && args.size == 1) {
            getPendingRequests(sender.name).map { (requester) -> requester }
        } else {
            null
        }
    }

    private fun getPendingRequests(player: String): List<Pair<String, TeleportAskType>> {
        return redis.smembers("tpa:requests:$player").mapNotNull { requester ->
            redis.get("tpa:request:$requester:$player")?.let {
                requester to TeleportAskType.valueOf(it)
            } ?: run {
                redis.srem("tpa:requests:$player", requester)
                redis.srem("tpa:outgoing_requests:$requester", player)
                null
            }
        }
    }

    private fun clearRequest(requester: String, target: String) {
        redis.del("tpa:request:$requester:$target")
        redis.srem("tpa:requests:$target", requester)
        redis.srem("tpa:outgoing_requests:$requester", target)
    }
}
