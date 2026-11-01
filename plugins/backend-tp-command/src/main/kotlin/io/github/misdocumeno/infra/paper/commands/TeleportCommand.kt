package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.GlobalLocationResolver
import io.github.misdocumeno.application.Teleporter
import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.infra.redis.RedisUsernameRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TeleportCommand(
    private val usernames: UsernameRepository,
    private val locations: GlobalLocationResolver,
    private val teleporter: Teleporter,
    private val scope: CoroutineScope,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args.size > 2) return false
        if (sender !is Player && args.size != 2) return false

        val toMove = if (args.size == 2) args[0] else sender.name
        val target = if (args.size == 2) args[1] else args[0]

        val toMoveName = usernames.findCaseInsensitive(toMove) ?: usernames.findPartial(toMove)
        val targetName = usernames.findCaseInsensitive(target) ?: usernames.findPartial(target)

        when {
            toMoveName == null -> {
                // TODO: component message, via config.yml
                sender.sendMessage("Player '$toMove' not found.")
                return true
            }

            targetName == null -> {
                sender.sendMessage("Player '$target' not found.")
                return true
            }

            toMoveName == targetName -> {
                sender.sendMessage("Cannot teleport a player to themselves.")
                return true
            }
        }

        scope.launch {
            val destination = try {
                locations.getPlayerLocation(targetName)
            } catch (e: TimeoutCancellationException) {
                sender.sendMessage("Cannot teleport right now, try again in a sec.")
                return@launch
            }

            if (destination == null) {
                sender.sendMessage("Player '$targetName' not found.")
                return@launch
            }

            sender.sendMessage("Teleporting $toMoveName")
            teleporter.teleport(toMoveName, destination)
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>
    ): List<String>? {
        return if (args.size <= 2) {
            args.lastOrNull()?.let { usernames.findPartialMultiple(it) }
        } else {
            null
        }
    }
}