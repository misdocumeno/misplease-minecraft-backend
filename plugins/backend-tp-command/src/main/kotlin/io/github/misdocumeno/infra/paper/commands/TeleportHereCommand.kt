package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.Teleporter
import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.domain.model.GlobalLocation
import io.github.misdocumeno.infra.paper.globalLocation
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TeleportHereCommand(
    private val usernames: UsernameRepository,
    private val teleporter: Teleporter,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }

        if (args.size != 1) return false

        val toMove = args.first()
        val toMoveName = usernames.findCaseInsensitive(toMove) ?: usernames.findPartial(toMove)

        if (toMoveName == null) {
            sender.sendMessage("Player '$toMove' not found.")
            return true
        }

        if (toMoveName == sender.name) {
            sender.sendMessage("Cannot teleport to yourself.")
            return true
        }

        sender.sendMessage("Teleporting $toMoveName")
        teleporter.teleport(toMoveName, sender.globalLocation)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>
    ): List<String>? {
        return if (sender is Player && args.size == 1) {
            usernames.findPartialMultiple(args.last()).filter { it != sender.name }
        } else {
            null
        }
    }
}