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
        // Paper often uses "{} issued server command: {}" as format — /login only appears
        // in formattedMessage / parameters. Check all of those.
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

    fun install() {
        if (installed) return
        val root = LogManager.getRootLogger() as? Logger ?: return
        start()
        root.addFilter(this)
        installed = true
    }
}
