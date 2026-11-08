package io.github.misdocumeno.infra.paper.commands

import io.github.misdocumeno.application.UsernameRepository
import io.github.misdocumeno.domain.model.TeleportAskNotification
import io.github.misdocumeno.domain.model.TeleportAskType
import io.github.misdocumeno.infra.paper.TeleportMessages
import io.github.misdocumeno.infra.paper.runSync
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class TeleportAskCommand(
    private val plugin: Plugin,
    private val scope: CoroutineScope,
    private val usernames: UsernameRepository,
    private val redis: RedisCommands<String, String>,
    private val json: Json,
    private val messages: TeleportMessages,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            messages.playerOnly(sender)
            return true
        }

        if (args.size != 1) return false

        val toAsk = args.first()
        val toAskName = usernames.findCaseInsensitive(toAsk) ?: usernames.findPartial(toAsk)

        if (toAskName == null) {
            messages.playerNotFound(sender, toAsk)
            return true
        }

        if (toAskName == sender.name) {
            messages.cannotSelf(sender)
            return true
        }

        val from = sender.name
        scope.launch {
            val asked =
                redis.set("tpa:request:$from:$toAskName", TeleportAskType.TPA.name, SetArgs.Builder.nx().ex(60))

            if (asked != "OK") {
                plugin.runSync {
                    if (sender.isOnline) messages.tpaAlready(sender, toAskName)
                }
                return@launch
            }

            redis.sadd("tpa:requests:$toAskName", from)
            redis.sadd("tpa:outgoing_requests:$from", toAskName)

            val notification = TeleportAskNotification(from, toAskName, TeleportAskType.TPA)
            redis.publish("tpa:request", json.encodeToString(notification))

            plugin.runSync {
                if (sender.isOnline) messages.tpaSent(sender, toAskName)
            }
        }

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
