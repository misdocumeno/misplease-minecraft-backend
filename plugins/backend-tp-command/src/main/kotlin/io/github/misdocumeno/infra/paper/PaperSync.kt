package io.github.misdocumeno.infra.paper

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/** Hop Redis/IO work back onto the server thread before touching players. */
fun Plugin.runSync(block: () -> Unit) {
    if (Bukkit.isPrimaryThread()) {
        block()
    } else {
        Bukkit.getScheduler().runTask(this, Runnable(block))
    }
}
