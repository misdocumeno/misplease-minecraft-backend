package io.github.misdocumeno.infra.libertybans

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Feedback for a muted player trying to speak privately. Adventure components are
 * appended as literal text, so a staff-written reason cannot inject formatting.
 *
 * Zone/format mirror the global chat tooltips (`MISPLEASE_TIME_ZONE`,
 * `MISPLEASE_TIME_FORMAT`) so both surfaces show the same wall-clock time.
 */
object MuteFeedback {
    private val zone: ZoneId = env("MISPLEASE_TIME_ZONE")
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.of("America/Argentina/Buenos_Aires")
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern(env("MISPLEASE_TIME_FORMAT") ?: "yyyy/MM/dd HH:mm:ss")

    fun component(mute: MuteState): Component {
        val builder = Component.text()
            .append(Component.text("You are muted", NamedTextColor.RED))

        val endDate = mute.endDate
        if (endDate == null) {
            builder.append(Component.text(" (permanent)", NamedTextColor.GRAY))
        } else {
            val at = endDate.atZone(zone)
            builder.append(
                Component.text(
                    " (until ${timeFormat.format(at)} (${zoneLabel(at.offset)}))",
                    NamedTextColor.GRAY,
                ),
            )
        }

        val reason = mute.reason
            ?.replace('\r', ' ')
            ?.replace('\n', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (reason != null) {
            builder.append(Component.text(" Reason: ", NamedTextColor.GRAY))
                .append(Component.text(reason, NamedTextColor.WHITE))
        }
        return builder.build()
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
