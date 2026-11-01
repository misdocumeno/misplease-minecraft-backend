package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.AuthTimeout
import io.github.misdocumeno.application.PlayerHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bukkit.plugin.Plugin

class BukkitAuthTimeout(
    private val scope: CoroutineScope,
    private val plugin: Plugin,
    private val config: BukkitConfig,
) : AuthTimeout {

    private val jobs = mutableMapOf<String, Job>()

    override fun startTimeout(player: PlayerHandle) {
        if (jobs.containsKey(player.username)) return

        jobs[player.username] = scope.launch {
            delay(30_000)
            plugin.server.scheduler.runTask(plugin) { ->
                val p = (player as BukkitPlayerHandle).unwrap()
                p.kick(config.timeoutKickMessage())
            }
        }
    }

    override fun cancelTimeout(player: PlayerHandle) {
        jobs.remove(player.username)?.cancel()
    }
}