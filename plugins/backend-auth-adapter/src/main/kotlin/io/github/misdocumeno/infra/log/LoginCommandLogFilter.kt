package io.github.misdocumeno.infra.log

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.filter.AbstractFilter

/**
 * Drops Spigot/Paper "issued server command: /login …" lines so passwords never hit the console log.
 */
object LoginCommandLogFilter : AbstractFilter() {

    private val sensitive = Regex(
        """issued server command:\s*/(?:login|register)\b""",
        RegexOption.IGNORE_CASE,
    )

    override fun filter(event: LogEvent): Filter.Result {
        val msg = event.message?.formattedMessage ?: return Filter.Result.NEUTRAL
        return if (sensitive.containsMatchIn(msg)) Filter.Result.DENY else Filter.Result.NEUTRAL
    }

    fun install() {
        val root = LogManager.getRootLogger() as? Logger ?: return
        root.addFilter(this)
        (LogManager.getLogger("net.minecraft.server.MinecraftServer") as? Logger)?.addFilter(this)
        (LogManager.getLogger("Minecraft") as? Logger)?.addFilter(this)
    }
}
