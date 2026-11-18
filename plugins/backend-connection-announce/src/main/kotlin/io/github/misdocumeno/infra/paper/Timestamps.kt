package io.github.misdocumeno.infra.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Local-time tooltip shared by the join/leave and death lines. Global chat does the
 * same on the proxy (see `ChatFormatter` in velocity-chat-control) — the two plugins are
 * standalone, so the tiny helper is duplicated on purpose.
 *
 * The Java protocol never sends the client's time zone (and Bedrock through Geyser does
 * not either), and a hover is baked once for every recipient, so "my local time" is not
 * obtainable. One network zone, stated explicitly in the text, is the honest option.
 *
 * `MISPLEASE_TIME_ZONE` (default America/Argentina/Buenos_Aires; the containers run on
 * UTC) and `MISPLEASE_TIME_FORMAT` (default `yyyy/MM/dd HH:mm:ss`, 24-hour clock). The
 * zone's offset is appended automatically (` (UTC-3)`).
 */
object Timestamps {

    private val zone: ZoneId =
        env("MISPLEASE_TIME_ZONE")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.of("America/Argentina/Buenos_Aires")

    private val format: DateTimeFormatter =
        DateTimeFormatter.ofPattern(env("MISPLEASE_TIME_FORMAT") ?: "yyyy/MM/dd HH:mm:ss")

    fun hover(at: Instant = Instant.now()): HoverEvent<Component> =
        Component.text(stamp(at)).color(NamedTextColor.YELLOW).asHoverEvent()

    private fun stamp(at: Instant): String {
        val zoned = at.atZone(zone)
        return "${format.format(zoned)} (${zoneLabel(zoned.offset)})"
    }

    private fun zoneLabel(offset: ZoneOffset): String {
        val seconds = offset.totalSeconds
        if (seconds == 0) return "UTC"
        val abs = kotlin.math.abs(seconds)
        val hours = abs / 3600
        val minutes = (abs % 3600) / 60
        val sign = if (seconds < 0) "-" else "+"
        return if (minutes == 0) {
            "UTC$sign$hours"
        } else {
            "UTC$sign$hours:${minutes.toString().padStart(2, '0')}"
        }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}
