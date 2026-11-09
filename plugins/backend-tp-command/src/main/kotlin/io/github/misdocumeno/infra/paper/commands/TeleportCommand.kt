package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.GlobalLocationResolver
import io.github.misdocumeno.application.Teleporter
import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.infra.paper.TeleportMessages
import io.github.misdocumeno.infra.paper.runSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class TeleportCommand(
    private val plugin: Plugin,
    private val usernames: UsernameRepository,
    private val locations: GlobalLocationResolver,
    private val teleporter: Teleporter,
    private val scope: CoroutineScope,
    private val messages: TeleportMessages,
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
                messages.playerNotFound(sender, toMove)
                return true
            }

            targetName == null -> {
                messages.playerNotFound(sender, target)
                return true
            }

            toMoveName == targetName -> {
                messages.cannotSelfOther(sender)
                return true
            }
        }

        scope.launch {
            val destination = try {
                locations.getPlayerLocation(targetName)
            } catch (_: TimeoutCancellationException) {
                plugin.runSync { messages.busy(sender) }
                return@launch
            }

            if (destination == null) {
                plugin.runSync { messages.playerNotFound(sender, targetName) }
                return@launch
            }

            plugin.runSync {
                messages.teleportingPlayer(sender, toMoveName)
                teleporter.teleport(toMoveName, destination, messages.arrivalFeedback(sender))
            }
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>
    ): List<String> {
        if (args.isEmpty() || args.size > 2) return emptyList()
        return usernames.suggest(args.last())
    }
}
