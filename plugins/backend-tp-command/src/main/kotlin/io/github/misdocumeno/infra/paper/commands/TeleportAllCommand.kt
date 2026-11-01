package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.GlobalLocationResolver
import io.github.misdocumeno.application.Teleporter
import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.infra.paper.globalLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TeleportAllCommand(
    private val usernames: UsernameRepository,
    private val locations: GlobalLocationResolver,
    private val teleporter: Teleporter,
    private val scope: CoroutineScope,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.size > 1) return false

        if (args.isEmpty()) {
            if (sender !is Player) {
                sender.sendMessage("Only players can use this command without specifying a target.")
                return true
            }

            sender.sendMessage("Teleporting everyone to you")
            usernames.getAllNames().forEach {
                teleporter.teleport(it, sender.globalLocation)
            }

            return true
        }

        val targetName = usernames.findCaseInsensitive(args.first()) ?: usernames.findPartial(args.first())

        if (targetName == null) {
            sender.sendMessage("Player '${args.first()}' not found.")
            return true
        }

        sender.sendMessage("Teleporting everyone to $targetName")

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

            usernames.getAllNames().forEach {
                teleporter.teleport(it, destination)
            }
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>
    ): List<String>? {
        return if (args.size == 1) {
            usernames.findPartialMultiple(args.last())
        } else {
            null
        }
    }
}