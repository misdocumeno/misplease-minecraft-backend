package io.github.misdocumeno

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restart the JVM after [maxAwakeSeconds] of *awake* time, once it is empty.
 *
 * A calendar slot does not fit hibernation: a server can be frozen for days, and
 * a missed 05:00 would otherwise fire the moment a player wakes it and leaves.
 * The counter only advances while the scheduler runs, so frozen time does not
 * count. It is in-memory on purpose: any JVM restart (deploy, crash, this
 * restarter) resets it, which is exactly the event this plugin schedules.
 *
 * Docker `restart: unless-stopped` brings the JVM back after the stop.
 */
class BackendRestarterPlugin : JavaPlugin() {
    private val stopping = AtomicBoolean(false)
    private var maxAwakeSeconds: Long = 24L * 3600
    private var emptySeconds: Long = 60
    private var warnMessage: String = ""

    private var awakeSeconds: Long = 0
    private var due: Boolean = false
    private var emptySinceMs: Long? = null

    override fun onEnable() {
        saveDefaultConfig()
        // CI smoke: an empty server must not stop itself during the smoke boot.
        if (isSmokeBoot()) {
            logger.info("restarter disabled (smoke boot)")
            return
        }
        reloadFromConfig()
        // Every 10 s is enough for a 60 s empty gate; it also bounds the awake
        // counter's granularity to 10 s, which is irrelevant at 24 h.
        server.scheduler.runTaskTimer(this, Runnable { tick() }, TICK_TICKS, TICK_TICKS)
        logger.info(
            "restarter enabled=${config.getBoolean("enabled")} " +
                "maxAwake=${maxAwakeSeconds / 3600}h empty-seconds=$emptySeconds",
        )
    }

    private fun isSmokeBoot(): Boolean {
        if (System.getenv("SMOKE_PLAIN") == "1") return true
        val serverId = System.getenv("SERVER_ID")?.trim().orEmpty()
        return serverId.equals("smoke", ignoreCase = true)
    }

    private fun reloadFromConfig() {
        reloadConfig()
        maxAwakeSeconds = (config.getLong("max-awake-hours").takeIf { it > 0 } ?: 24L) * 3600
        emptySeconds = config.getLong("empty-seconds").coerceAtLeast(10)
        warnMessage = config.getString("warn-message")?.trim().orEmpty()
    }

    private fun tick() {
        if (!config.getBoolean("enabled") || stopping.get()) return

        awakeSeconds += TICK_SECONDS
        if (!due && RestarterDecision.isDue(awakeSeconds, maxAwakeSeconds)) {
            due = true
            logger.info(
                "restarter: ${awakeSeconds / 3600}h awake, waiting for empty",
            )
        }
        if (!due) return

        val online = Bukkit.getOnlinePlayers().size
        if (online > 0) {
            if (emptySinceMs != null) {
                logger.info("restarter: $online player(s) online — empty timer reset (still waiting)")
            }
            emptySinceMs = null
            return
        }

        val nowMs = System.currentTimeMillis()
        val since = emptySinceMs ?: nowMs.also { emptySinceMs = it }
        val waitedSec = (nowMs - since) / 1000
        if (!RestarterDecision.shouldStop(due, online, emptySeconds, waitedSec)) return

        if (!stopping.compareAndSet(false, true)) return
        logger.info(
            "restarter: ${awakeSeconds / 3600}h awake and empty for ${waitedSec}s — graceful stop",
        )
        if (warnMessage.isNotEmpty()) {
            val component = LegacyComponentSerializer.legacyAmpersand().deserialize(warnMessage)
            Bukkit.getServer().sendMessage(component)
        }
        server.scheduler.runTaskLater(this, Runnable {
            Bukkit.shutdown()
        }, 40L)
    }

    private companion object {
        const val TICK_SECONDS = 10L
        const val TICK_TICKS = 20L * 10
    }
}
