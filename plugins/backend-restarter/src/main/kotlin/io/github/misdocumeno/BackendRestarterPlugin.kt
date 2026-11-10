package io.github.misdocumeno

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Arms once per day at [window-start]; stops the JVM after the server has been
 * empty for [empty-seconds]. Docker brings the container back.
 */
class BackendRestarterPlugin : JavaPlugin() {
    private val stopping = AtomicBoolean(false)
    private var zone: ZoneId = ZoneId.systemDefault()
    private var windowStart: LocalTime = LocalTime.of(5, 0)
    private var emptySeconds: Long = 60
    private var warnMessage: String = ""
    private var armedDate: LocalDate? = null
    private var completedDate: LocalDate? = null
    private var emptySinceMs: Long? = null

    override fun onEnable() {
        saveDefaultConfig()
        reloadFromConfig()
        // Every 10s is enough for a 60s empty gate.
        server.scheduler.runTaskTimer(this, Runnable { tick() }, 20L * 10, 20L * 10)
        logger.info(
            "restarter enabled=${config.getBoolean("enabled")} " +
                "tz=$zone window-start=$windowStart empty-seconds=$emptySeconds",
        )
    }

    private fun reloadFromConfig() {
        reloadConfig()
        zone = runCatching { ZoneId.of(config.getString("timezone") ?: "UTC") }
            .getOrElse { ZoneId.systemDefault() }
        windowStart = parseTime(config.getString("window-start") ?: "05:00")
        emptySeconds = config.getLong("empty-seconds").coerceAtLeast(10)
        warnMessage = config.getString("warn-message")?.trim().orEmpty()
    }

    private fun tick() {
        if (!config.getBoolean("enabled") || stopping.get()) return

        val now = Clock.system(zone).instant().atZone(zone)
        val today = now.toLocalDate()
        val time = now.toLocalTime()

        if (completedDate == today) return

        if (armedDate != today && !time.isBefore(windowStart)) {
            armedDate = today
            emptySinceMs = null
            logger.info("restarter armed for $today (window-start=$windowStart)")
        }

        if (armedDate != today) return

        val online = Bukkit.getOnlinePlayers().size
        if (online > 0) {
            if (emptySinceMs != null) {
                logger.info("restarter: $online player(s) online — empty timer reset")
            }
            emptySinceMs = null
            return
        }

        val nowMs = System.currentTimeMillis()
        val since = emptySinceMs ?: nowMs.also { emptySinceMs = it }
        val waitedSec = (nowMs - since) / 1000
        if (waitedSec < emptySeconds) return

        if (!stopping.compareAndSet(false, true)) return
        logger.info("restarter: empty for ${waitedSec}s — graceful stop")
        if (warnMessage.isNotEmpty()) {
            val component = LegacyComponentSerializer.legacyAmpersand().deserialize(warnMessage)
            Bukkit.getServer().sendMessage(component)
        }
        completedDate = today
        // Next tick after a short delay so the warn can flush; then stop.
        server.scheduler.runTaskLater(this, Runnable {
            Bukkit.shutdown()
        }, 40L)
    }

    private fun parseTime(raw: String): LocalTime =
        runCatching { LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("H:mm")) }
            .recoverCatching { LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("HH:mm")) }
            .getOrElse { LocalTime.of(5, 0) }
}
