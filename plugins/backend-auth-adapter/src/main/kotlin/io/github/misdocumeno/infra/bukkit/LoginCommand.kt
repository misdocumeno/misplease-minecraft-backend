package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.AuthFeedback
import io.github.misdocumeno.application.LoginPlayer
import io.github.misdocumeno.application.SessionEvent
import io.github.misdocumeno.application.SessionManager
import io.github.misdocumeno.domain.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class LoginCommand(
    private val sessionManager: SessionManager,
    private val loginPlayer: LoginPlayer,
    private val feedback: AuthFeedback,
    private val scope: CoroutineScope,
    private val plugin: Plugin,
) : CommandExecutor, TabCompleter {

    // TODO: do not log "<user> issued /login <pass> command"
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        if (args.isEmpty()) return false

        val session = sessionManager.get(sender.uniqueId) ?: return true

        if (session.snapshot().authState == AuthState.AUTHENTICATED) {
            feedback.onAlreadyLoggedIn(BukkitPlayerHandle(sender))
            return true
        }

        scope.launch {
            val ok = loginPlayer.execute(sender.name, args[0])
            plugin.server.scheduler.runTask(plugin) { ->
                if (ok) {
                    session.dispatch(SessionEvent.LoginSucceeded)
                } else {
                    feedback.onWrongPassword(BukkitPlayerHandle(sender))
                }
            }
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, label: String, args: Array<out String>
    ) = listOf<String>()
}
