package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.domain.model.TeleportAskType
import io.lettuce.core.api.sync.RedisCommands
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TeleportCancelCommand(
    private val usernames: UsernameRepository,
    private val redis: RedisCommands<String, String>,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }

        if (args.size > 1) return false

        val outgoing = getPendingOutgoingRequests(sender.name)

        if (outgoing.isEmpty()) {
            sender.sendMessage("No requests to cancel.")
            return true
        }

        if (args.isEmpty()) {
            if (outgoing.size > 1) {
                sender.sendMessage("Specify which request to cancel, or use \"*\" to cancel all.")
                return true
            }

            val (target) = outgoing.first()
            clearRequest(sender.name, target)
            sender.sendMessage("$target request cancelled.")
            return true
        }

        if (args.first() == "*") {
            sender.sendMessage("Rejecting all requests.")
            outgoing.forEach { (target) -> clearRequest(sender.name, target) }
            return true
        }

        val target =
            usernames.findCaseInsensitive(args.first()) ?: usernames.findPartial(args.first()) ?: args.first()

        if (!outgoing.any { (it) -> it == target }) {
            sender.sendMessage("No requests to $target.")
            return true
        }

        clearRequest(sender.name, target)
        sender.sendMessage("$target request cancelled.")
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>,
    ): List<String>? {
        return if (sender is Player && args.size == 1) {
            getPendingOutgoingRequests(sender.name).map { (target) -> target }
        } else {
            null
        }
    }

    private fun getPendingOutgoingRequests(requester: String): List<Pair<String, TeleportAskType>> {
        return redis.smembers("tpa:outgoing_requests:$requester").mapNotNull { target ->
            redis.get("tpa:request:$requester:$target")?.let {
                target to TeleportAskType.valueOf(it)
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