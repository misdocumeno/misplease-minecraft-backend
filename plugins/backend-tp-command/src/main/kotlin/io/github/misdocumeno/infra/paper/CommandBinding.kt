package io.github.misdocumeno.infra.paper

import org.bukkit.command.CommandExecutor
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter

/** setExecutor alone does not register tab-complete; vanilla same-server names win. */
fun <T> PluginCommand.bind(handler: T) where T : CommandExecutor, T : TabCompleter {
    setExecutor(handler)
    setTabCompleter(handler)
}
