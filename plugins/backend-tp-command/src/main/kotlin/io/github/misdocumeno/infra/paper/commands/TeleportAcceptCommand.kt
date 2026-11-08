package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.GlobalLocationResolver
import io.github.misdocumeno.application.Teleporter
import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.domain.model.TeleportAskType
import io.github.misdocumeno.infra.paper.TeleportMessages
import io.github.misdocumeno.infra.paper.globalLocation
import io.github.misdocumeno.infra.paper.runSync
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class TeleportAcceptCommand(
    private val plugin: Plugin,
    private val usernames: UsernameRepository,
    private val locations: GlobalLocationResolver,
    private val teleporter: Teleporter,
    private val redis: RedisCommands<String, String>,
    private val scope: CoroutineScope,
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
                    requests.isEmpty() -> messages.noRequestsAccept(sender)
                    arg == null && requests.size > 1 -> messages.specifyAccept(sender)
                    arg == null -> {
                        val (requester, type) = requests.first()
                        performTeleport(requester, sender, type)
                        scope.launch { clearRequest(requester, target) }
                        messages.accepted(sender, requester)
                    }
                    arg == "*" -> {
                        messages.acceptedAll(sender)
                        requests.forEach { (requester, type) ->
                            performTeleport(requester, sender, type)
                            scope.launch { clearRequest(requester, target) }
                        }
                    }
                    else -> {
                        val requester =
                            usernames.findCaseInsensitive(arg) ?: usernames.findPartial(arg) ?: arg
                        val request = requests.find { (name) -> name == requester }
                        if (request == null) {
                            messages.noRequestFrom(sender, requester)
                        } else {
                            performTeleport(request.first, sender, request.second)
                            scope.launch { clearRequest(requester, target) }
                            messages.accepted(sender, requester)
                        }
                    }
                }
            }
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>
    ): List<String>? {
        return if (sender is Player && args.size == 1) {
            getPendingRequests(sender.name).map { (requester) -> requester }
        } else {
            null
        }
    }

    private fun performTeleport(requester: String, receiver: Player, type: TeleportAskType) {
        val feedback = messages.arrivalFeedback(receiver)
        when (type) {
            TeleportAskType.TPA -> {
                teleporter.teleport(requester, receiver.globalLocation, feedback)
            }
            TeleportAskType.TPA_HERE -> {
                scope.launch {
                    try {
                        locations.getPlayerLocation(requester)
                    } catch (_: TimeoutCancellationException) {
                        plugin.runSync { if (receiver.isOnline) messages.busy(receiver) }
                        return@launch
                    }?.let {
                        teleporter.teleport(receiver.name, it, feedback)
                    } ?: plugin.runSync {
                        if (receiver.isOnline) messages.playerNotFound(receiver, requester)
                    }
                }
            }
        }
    }

    private fun getPendingRequests(target: String): List<Pair<String, TeleportAskType>> {
        return redis.smembers("tpa:requests:$target").mapNotNull { requester ->
            redis.get("tpa:request:$requester:$target")?.let {
                requester to TeleportAskType.valueOf(it)
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
