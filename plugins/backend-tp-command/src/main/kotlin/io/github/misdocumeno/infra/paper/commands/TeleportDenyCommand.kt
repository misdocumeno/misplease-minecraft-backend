package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.domain.model.TeleportAskType
import io.github.misdocumeno.infra.paper.TeleportMessages
import io.lettuce.core.api.sync.RedisCommands
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TeleportDenyCommand(
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

        val requests = getPendingRequests(sender.name)

        if (requests.isEmpty()) {
            messages.noRequestsDeny(sender)
            return true
        }

        if (args.isEmpty()) {
            if (requests.size > 1) {
                messages.specifyDeny(sender)
                return true
            }

            val (requester) = requests.first()
            clearRequest(requester, sender.name)
            messages.denied(sender, requester)
            return true
        }

        if (args.first() == "*") {
            messages.deniedAll(sender)
            requests.forEach { (requester) -> clearRequest(requester, sender.name) }
            return true
        }

        val requester =
            usernames.findCaseInsensitive(args.first()) ?: usernames.findPartial(args.first()) ?: args.first()

        if (!requests.any { (it) -> it == requester }) {
            messages.noRequestFrom(sender, requester)
            return true
        }

        clearRequest(requester, sender.name)
        messages.denied(sender, requester)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>,
    ): List<String>? {
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
