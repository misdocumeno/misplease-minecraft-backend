package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.GlobalLocationResolver
import io.github.misdocumeno.application.Teleporter
import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.infra.paper.TeleportMessages
import io.github.misdocumeno.infra.paper.globalLocation
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

class TeleportAllCommand(
    private val plugin: Plugin,
    private val usernames: UsernameRepository,
    private val locations: GlobalLocationResolver,
    private val teleporter: Teleporter,
    private val scope: CoroutineScope,
    private val messages: TeleportMessages,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.size > 1) return false

        if (args.isEmpty()) {
            if (sender !is Player) {
                messages.playerOnlyWithTarget(sender)
                return true
            }

            messages.teleportingEveryoneYou(sender)
            val feedback = messages.arrivalFeedback(sender)
            usernames.getAllNames().forEach {
                teleporter.teleport(it, sender.globalLocation, feedback)
            }

            return true
        }

        val targetName = usernames.findCaseInsensitive(args.first()) ?: usernames.findPartial(args.first())

        if (targetName == null) {
            messages.playerNotFound(sender, args.first())
            return true
        }

        messages.teleportingEveryoneTo(sender, targetName)

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
                val feedback = messages.arrivalFeedback(sender)
                usernames.getAllNames().forEach {
                    teleporter.teleport(it, destination, feedback)
                }
            }
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>
    ): List<String> {
        if (args.size != 1) return emptyList()
        return usernames.suggest(args.last())
    }
}
