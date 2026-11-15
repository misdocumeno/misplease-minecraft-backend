package io.github.misdocumeno.infra.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import io.github.misdocumeno.infra.redis.RedisAuthSet
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * Proxy-side gate while Redis has no session: block every Velocity command except
 * `/login` (forwarded to the backend), hide them from the client command tree, and
 * block server switches after the first join.
 */
class UnauthCommandGate(
    private val auth: RedisAuthSet,
) {
    @Subscribe
    fun onCommand(event: CommandExecuteEvent) {
        val player = event.commandSource as? Player ?: return
        if (auth.isAuthenticated(player.username)) return

        val root = event.command.trimStart().substringBefore(' ').substringBefore(':').lowercase()
        if (root == "login") return

        event.result = CommandExecuteEvent.CommandResult.denied()
        player.sendMessage(LOGIN_FIRST)
    }

    @Subscribe
    fun onAvailableCommands(event: PlayerAvailableCommandsEvent) {
        if (auth.isAuthenticated(event.player.username)) return
        // Brigadier exposes children as an unmodifiable view; strip via the backing maps.
        stripCommandsExceptLogin(event.rootNode)
    }

    private fun stripCommandsExceptLogin(root: com.mojang.brigadier.tree.RootCommandNode<*>) {
        val names = root.children.map { it.name }.filterNot { isLoginCommand(it) }
        if (names.isEmpty()) return
        for (fieldName in listOf("children", "literals", "arguments")) {
            val field = com.mojang.brigadier.tree.CommandNode::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(root) as MutableMap<String, *>
            names.forEach { map.remove(it) }
        }
    }

    @Subscribe
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        // First proxy→backend join (incl. last-session) must proceed so they can /login.
        if (event.previousServer == null) return
        if (auth.isAuthenticated(event.player.username)) return

        event.result = ServerPreConnectEvent.ServerResult.denied()
        event.player.sendMessage(LOGIN_FIRST)
    }

    private fun isLoginCommand(name: String): Boolean {
        val simple = name.substringAfterLast(':').lowercase()
        return simple == "login"
    }

    companion object {
        private val LOGIN_FIRST: Component =
            Component.text("Usá /login antes de usar comandos o cambiar de servidor.", NamedTextColor.RED)
    }
}
