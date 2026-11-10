package io.github.misdocumeno.infra.log

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Marker
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.filter.AbstractFilter
import org.apache.logging.log4j.message.Message

/**
 * Drops Spigot/Paper "issued server command: /login|register …" lines so passwords
 * never hit the console log.
 *
 * Must not be installed during onEnable: attaching a Log4j filter while Paper is still
 * booting (async Log4j + POSTWORLD enable) hangs the server before `Done (` — proven by
 * bisect (green 68868e3 → first bad 8237765; isolate: only this filter fails smoke,
 * TeleportCommand tab-complete alone stays green).
 */
object LoginCommandLogFilter : AbstractFilter() {

    private val sensitive = Regex(
        """issued server command:\s*/(?:login|register)\b""",
        RegexOption.IGNORE_CASE,
    )

    @Volatile
    private var installed = false

    private fun decide(message: String?): Filter.Result {
        if (message.isNullOrEmpty()) return Filter.Result.NEUTRAL
        return if (sensitive.containsMatchIn(message)) Filter.Result.DENY else Filter.Result.NEUTRAL
    }

    /** Prefer format over formattedMessage — avoids param rendering on the async log path. */
    private fun text(msg: Message?): String? {
        if (msg == null) return null
        return msg.format ?: msg.formattedMessage
    }

    override fun filter(event: LogEvent): Filter.Result =
        decide(text(event.message))

    override fun filter(
        logger: Logger,
        level: Level,
        marker: Marker?,
        msg: String?,
        throwable: Throwable?,
    ): Filter.Result = decide(msg)

    override fun filter(
        logger: Logger,
        level: Level,
        marker: Marker?,
        msg: String?,
        vararg params: Any?,
    ): Filter.Result = decide(msg)

    override fun filter(
        logger: Logger,
        level: Level,
        marker: Marker?,
        msg: Message?,
        throwable: Throwable?,
    ): Filter.Result = decide(text(msg))

    override fun filter(
        logger: Logger,
        level: Level,
        marker: Marker?,
        msg: Any?,
        throwable: Throwable?,
    ): Filter.Result = decide(msg?.toString())

    fun install() {
        if (installed) return
        val root = LogManager.getRootLogger() as? Logger ?: return
        start()
        root.addFilter(this)
        installed = true
    }
}
