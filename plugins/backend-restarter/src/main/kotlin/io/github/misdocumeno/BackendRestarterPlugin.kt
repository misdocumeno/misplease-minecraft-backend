package io.github.misdocumeno

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every calendar day at [at] (local [timezone]), declare one restart intent.
 * If the server is empty (≥ [empty-seconds]), stop now. If not, stay pending and
 * stop at the first later moment it has been empty that long — even days later.
 * Missed days do not stack (one pending max). State survives JVM restarts via
 * `last-completed` in the data folder so Docker bounce does not loop.
 */
class BackendRestarterPlugin : JavaPlugin() {
    private val stopping = AtomicBoolean(false)
    private var zone: ZoneId = ZoneId.systemDefault()
    private var at: LocalTime = LocalTime.of(5, 0)
    private var emptySeconds: Long = 60
    private var warnMessage: String = ""

    /** Intent declared; waiting for empty grace. */
    private var pending: Boolean = false

    /** When we last finished a stop (UTC instant); persisted. */
    private var lastCompleted: Instant? = null

    private var emptySinceMs: Long? = null

    override fun onEnable() {
        saveDefaultConfig()
        // CI smoke: empty server near the daily `at` window must not pending-stop
        // before smoke-check sees "Done (".
        if (isSmokeBoot()) {
            logger.info("restarter disabled (smoke boot)")
            return
        }
        reloadFromConfig()
        lastCompleted = readLastCompleted()
        // Every 10s is enough for a 60s empty gate.
        server.scheduler.runTaskTimer(this, Runnable { tick() }, 20L * 10, 20L * 10)
        logger.info(
            "restarter enabled=${config.getBoolean("enabled")} " +
                "tz=$zone at=$at empty-seconds=$emptySeconds lastCompleted=$lastCompleted",
        )
    }

    private fun isSmokeBoot(): Boolean {
        if (System.getenv("SMOKE_PLAIN") == "1") return true
        val serverId = System.getenv("SERVER_ID")?.trim().orEmpty()
        return serverId.equals("smoke", ignoreCase = true)
    }

    private fun reloadFromConfig() {
        reloadConfig()
        zone = runCatching { ZoneId.of(config.getString("timezone") ?: "UTC") }
            .getOrElse { ZoneId.systemDefault() }
        // `at` preferred; `window-start` kept as alias for old configs.
        at = parseTime(
            config.getString("at")
                ?: config.getString("window-start")
                ?: "05:00",
        )
        emptySeconds = config.getLong("empty-seconds").coerceAtLeast(10)
        warnMessage = config.getString("warn-message")?.trim().orEmpty()
    }

    private fun tick() {
        if (!config.getBoolean("enabled") || stopping.get()) return

        val nowZoned = Clock.system(zone).instant().atZone(zone)
        val nowInstant = nowZoned.toInstant()
        val todayAt = nowZoned.toLocalDate().atTime(at).atZone(zone).toInstant()

        if (!pending && !nowInstant.isBefore(todayAt)) {
            val doneThisSlot = lastCompleted != null && !lastCompleted!!.isBefore(todayAt)
            if (!doneThisSlot) {
                pending = true
                emptySinceMs = null
                logger.info("restarter pending (daily at=$at tz=$zone)")
            }
        }

        if (!pending) return

        val online = Bukkit.getOnlinePlayers().size
        if (online > 0) {
            if (emptySinceMs != null) {
                logger.info("restarter: $online player(s) online — empty timer reset (still pending)")
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
        pending = false
        lastCompleted = Instant.now()
        writeLastCompleted(lastCompleted!!)
        server.scheduler.runTaskLater(this, Runnable {
            Bukkit.shutdown()
        }, 40L)
    }

    private fun lastCompletedFile() = dataFolder.resolve("last-completed")

    private fun readLastCompleted(): Instant? {
        val f = lastCompletedFile()
        if (!f.isFile) return null
        return runCatching { Instant.parse(f.readText().trim()) }
            .onFailure { logger.warning("ignoring bad last-completed: ${it.message}") }
            .getOrNull()
    }

    private fun writeLastCompleted(instant: Instant) {
        runCatching {
            Files.createDirectories(dataFolder.toPath())
            lastCompletedFile().writeText(instant.toString())
        }.onFailure { logger.warning("could not persist last-completed: ${it.message}") }
    }

    private fun parseTime(raw: String): LocalTime =
        runCatching { LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("H:mm")) }
            .recoverCatching { LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("HH:mm")) }
            .getOrElse { LocalTime.of(5, 0) }
}
