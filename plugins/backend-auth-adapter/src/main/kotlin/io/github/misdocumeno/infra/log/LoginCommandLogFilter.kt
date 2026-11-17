package io.github.misdocumeno.infra.log

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.filter.AbstractFilter
import org.apache.logging.log4j.core.filter.Filterable

/**
 * Drops Paper's "issued server command: /login|register …" lines so passwords never
 * reach `logs/latest.log` or the console.
 *
 * Source of the line (Paper, ServerGamePacketListenerImpl, gated by spigot.yml
 * `commands.log`):
 * `LOGGER.info("{} issued server command: {}", name, commandString)`
 * — a parameterized message, so the command lives in the parameters, not in the format.
 *
 * Must not be installed during onEnable: attaching a Log4j filter while Paper is still
 * booting hangs the server before `Done (` — proven by bisect (green 68868e3 → first bad
 * 8237765; isolate: only this filter fails smoke, TeleportCommand tab-complete alone
 * stays green). Install it ~1s after enable instead.
 *
 * Attaching to the root [LoggerConfig] is enough on its own because Paper's log4j2.xml
 * declares no per-logger entries — every logger inherits the root config, and
 * `LoggerConfig.addFilter` appends (Paper's own NETWORK_PACKETS MarkerFilter survives).
 * The appenders and explicit logger configs are covered too, so a future `<Logger>`
 * entry in a custom log4j2.xml cannot silently bypass the redaction.
 */
object LoginCommandLogFilter : AbstractFilter() {

    private val sensitive = Regex(
        """issued server command:\s*/(?:login|register)\b""",
        RegexOption.IGNORE_CASE,
    )

    @Volatile
    private var attachment: Attachment? = null

    override fun filter(event: LogEvent): Filter.Result {
        val message = event.message ?: return Filter.Result.NEUTRAL

        val format = message.format.orEmpty()
        if (sensitive.containsMatchIn(format)) return Filter.Result.DENY

        val formatted = runCatching { message.formattedMessage }.getOrNull().orEmpty()
        if (sensitive.containsMatchIn(formatted)) return Filter.Result.DENY

        val params = message.parameters ?: return Filter.Result.NEUTRAL
        for (param in params) {
            if (param != null && sensitive.containsMatchIn(param.toString())) {
                return Filter.Result.DENY
            }
        }
        return Filter.Result.NEUTRAL
    }

    /**
     * Attaches the filter to the live Log4j configuration. Idempotent.
     *
     * @return where it attached, or `null` when the server's Log4j context was not
     * reachable (then the caller should warn: passwords will be logged).
     */
    fun install(): Attachment? {
        attachment?.let { return it }

        val context = LogManager.getContext(false) as? LoggerContext ?: return null
        val configuration = context.configuration

        start()
        configuration.rootLogger.addFilter(this)

        var loggerConfigs = 0
        for (loggerConfig in configuration.loggers.values) {
            if (loggerConfig === configuration.rootLogger) continue
            loggerConfig.addFilter(this)
            loggerConfigs++
        }

        var appenders = 0
        for (appender in configuration.appenders.values) {
            if (appender is Filterable) {
                appender.addFilter(this)
                appenders++
            }
        }

        context.updateLoggers()

        return Attachment(loggerConfigs, appenders).also { attachment = it }
    }

    /** How many Log4j targets the filter was attached to. */
    data class Attachment(val loggerConfigs: Int, val appenders: Int)
}
