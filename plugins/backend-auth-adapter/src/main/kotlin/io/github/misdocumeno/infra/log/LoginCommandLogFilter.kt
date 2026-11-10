package io.github.misdocumeno.infra.log

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.filter.AbstractFilter

/**
 * Drops Spigot/Paper "issued server command: /login|register …" lines so passwords
 * never hit the console log.
 *
 * Must not be installed during onEnable: attaching a Log4j filter while Paper is still
 * booting hangs the server before `Done (` — proven by bisect (green 68868e3 → first bad
 * 8237765; isolate: only this filter fails smoke, TeleportCommand tab-complete alone
 * stays green).
 */
object LoginCommandLogFilter : AbstractFilter() {

    private val sensitive = Regex(
        """issued server command:\s*/(?:login|register)\b""",
        RegexOption.IGNORE_CASE,
    )

    @Volatile
    private var installed = false

    override fun filter(event: LogEvent): Filter.Result {
        // Prefer format over formattedMessage — avoid param rendering on the async log path.
        val msg = event.message?.format ?: event.message?.formattedMessage
            ?: return Filter.Result.NEUTRAL
        return if (sensitive.containsMatchIn(msg)) Filter.Result.DENY else Filter.Result.NEUTRAL
    }

    fun install() {
        if (installed) return
        val root = LogManager.getRootLogger() as? Logger ?: return
        start()
        root.addFilter(this)
        installed = true
    }
}
