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

class TeleportCancelCommand(
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

        val requester = sender.name
        val arg = args.firstOrNull()
        scope.launch {
            val requests = getOutgoingRequests(requester)
            plugin.runSync {
                if (!sender.isOnline) return@runSync
                when {
                    requests.isEmpty() -> messages.noRequestsCancel(sender)
                    arg == null && requests.size > 1 -> messages.specifyCancel(sender)
                    arg == null -> {
                        val (target) = requests.first()
                        scope.launch { clearRequest(requester, target) }
                        messages.cancelled(sender, target)
                    }
                    arg == "*" -> {
                        messages.cancelledAll(sender)
                        scope.launch { requests.forEach { (target) -> clearRequest(requester, target) } }
                    }
                    else -> {
                        val target =
                            usernames.findCaseInsensitive(arg) ?: usernames.findPartial(arg) ?: arg
                        if (requests.none { (name) -> name == target }) {
                            messages.noRequestTo(sender, target)
                        } else {
                            scope.launch { clearRequest(requester, target) }
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
    ): List<String>? {
        return if (sender is Player && args.size == 1) {
            getOutgoingRequests(sender.name).map { (target) -> target }
        } else {
            null
        }
    }

    private fun getOutgoingRequests(requester: String): List<Pair<String, TeleportAskType>> {
        return redis.smembers("tpa:outgoing_requests:$requester").mapNotNull { target ->
            redis.get("tpa:request:$requester:$target")?.let {
                target to TeleportAskType.valueOf(it)
            } ?: run {
                redis.srem("tpa:requests:$target", requester)
                redis.srem("tpa:outgoing_requests:$requester", target)
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
