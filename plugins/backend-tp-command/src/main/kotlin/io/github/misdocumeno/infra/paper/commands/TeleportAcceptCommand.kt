package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.GlobalLocationResolver
import io.github.misdocumeno.application.Teleporter
import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.domain.model.TeleportAskType
import io.github.misdocumeno.infra.paper.globalLocation
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TeleportAcceptCommand(
    private val usernames: UsernameRepository,
    private val locations: GlobalLocationResolver,
    private val teleporter: Teleporter,
    private val redis: RedisCommands<String, String>,
    private val scope: CoroutineScope,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }

        if (args.size > 1) return false

        val requests = getPendingRequests(sender.name)

        if (requests.isEmpty()) {
            sender.sendMessage("No requests to accept.")
            return true
        }

        if (args.isEmpty()) {
            if (requests.size > 1) {
                sender.sendMessage("Specify who to reject, or use \"*\" to reject all.")
                return true
            }

            val (requester, type) = requests.first()
            performTeleport(requester, sender, type)
            clearRequest(requester, sender.name)
            sender.sendMessage("$requester teleport accepted.")
            return true
        }

        if (args.first() == "*") {
            sender.sendMessage("Accepting all requests.")
            requests.forEach { (requester, type) ->
                performTeleport(requester, sender, type)
                clearRequest(requester, sender.name)
            }
            return true
        }

        val requester =
            usernames.findCaseInsensitive(args.first()) ?: usernames.findPartial(args.first()) ?: args.first()

        val request = requests.find { (it) -> it == requester }

        if (request == null) {
            sender.sendMessage("No requests from $requester.")
            return true
        }

        performTeleport(request.first, sender, request.second)
        clearRequest(requester, sender.name)
        sender.sendMessage("$requester request accepted.")
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
        when (type) {
            TeleportAskType.TPA -> {
                teleporter.teleport(requester, receiver.globalLocation)
            }

            TeleportAskType.TPA_HERE -> {
                scope.launch {
                    try {
                        locations.getPlayerLocation(requester)
                    } catch (_: TimeoutCancellationException) {
                        receiver.sendMessage("Cannot teleport right now, try again in a sec.")
                        return@launch
                    }?.let {
                        teleporter.teleport(receiver.name, it)
                    } ?: run {
                        receiver.sendMessage("Player '$requester' not found.")
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
                // delete expired requests
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