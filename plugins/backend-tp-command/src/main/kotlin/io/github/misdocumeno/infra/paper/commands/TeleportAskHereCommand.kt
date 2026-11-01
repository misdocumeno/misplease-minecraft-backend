package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.domain.model.TeleportAskNotification
import io.github.misdocumeno.domain.model.TeleportAskType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.Json
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TeleportAskHereCommand(
    private val usernames: UsernameRepository,
    private val redis: RedisCommands<String, String>,
    private val json: Json,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }

        if (args.size != 1) return false

        val toAsk = args.first()
        val toAskName = usernames.findCaseInsensitive(toAsk) ?: usernames.findPartial(toAsk)

        if (toAskName == null) {
            sender.sendMessage("Player '$toAsk' not found.")
            return true
        }

        if (toAskName == sender.name) {
            sender.sendMessage("Cannot teleport to yourself.")
            return true
        }

        val asked =
            redis.set(
                "tpa:request:${sender.name}:$toAskName",
                TeleportAskType.TPA_HERE.name,
                SetArgs.Builder.nx().ex(60)
            )

        if (asked != "OK") {
            sender.sendMessage("You already asked $toAskName")
            return true
        }

        redis.sadd("tpa:requests:$toAskName", sender.name)

        val notification = TeleportAskNotification(sender.name, toAskName, TeleportAskType.TPA_HERE)
        redis.publish("tpa:request", json.encodeToString(notification))
        sender.sendMessage("Asking $toAskName")

        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>
    ): List<String>? {
        return if (sender is Player && args.size == 1) {
            usernames.findPartialMultiple(args.first()).filter { it != sender.name }
        } else {
            null
        }
    }
}