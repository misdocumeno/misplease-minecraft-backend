package io.github.misdocumeno.application

import io.github.misdocumeno.domain.UsernameRepository
import io.github.misdocumeno.domain.model.ChatIntent
import io.github.misdocumeno.domain.model.RenderedChatMessage
import net.kyori.adventure.text.minimessage.MiniMessage
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Global chat line: `<name> message`, with the local time of the event on hover.
 *
 * [ChatIntent.timestamp] is the instant the backend accepted the message, so every
 * recipient sees the same time and no extra Redis field is needed. The time is baked
 * into the MiniMessage string that each backend deserializes, on top of the existing
 * payload — no protocol change.
 *
 * Zone and pattern are per network, not per server: `MISPLEASE_TIME_ZONE` (default
 * America/Argentina/Buenos_Aires, because the containers run on UTC) and
 * `MISPLEASE_TIME_FORMAT` (default `yyyy/MM/dd HH:mm:ss`, 24-hour clock). The zone's
 * offset is appended automatically (` (UTC-3)`), so the tooltip is unambiguous even
 * for someone reading it from another zone. Same pair as `Timestamps` in
 * backend-connection-announce (join/leave/death), which keeps all tooltips aligned.
 */
class ChatFormatter(
    private val usernames: UsernameRepository,
    private val zone: ZoneId = DEFAULT_ZONE,
    private val timeFormat: DateTimeFormatter = DEFAULT_TIME_FORMAT,
) {
    private val mm = MiniMessage.miniMessage()

    fun format(intent: ChatIntent): RenderedChatMessage {
        val name = intent.senderNameMiniMessage
            ?: "<gray>${usernames.getUsername(intent.senderUUID) ?: "<unknown>"}</gray>"
        // Literal <> brackets (gray). Only `<` needs MiniMessage escaping; `\>`
        // would show a visible backslash. Name keeps LP color; message is white.
        val line =
            "<gray>\\<</gray>$name<gray>></gray> <white>${mm.escapeTags(intent.rawMessage)}</white>"

        // Plain text inside the quotes: no nested tags to escape, and the hover cannot
        // inherit or leak the line's colors. Adventure renders it in the default style.
        val at = Instant.ofEpochMilli(intent.timestamp).atZone(zone)
        val time = "${timeFormat.format(at)} (${zoneLabel(at.offset)})"

        return RenderedChatMessage("<hover:show_text:'$time'>$line</hover>")
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

    companion object {
        val DEFAULT_ZONE: ZoneId =
            env("MISPLEASE_TIME_ZONE")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.of("America/Argentina/Buenos_Aires")

        val DEFAULT_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern(env("MISPLEASE_TIME_FORMAT") ?: "yyyy/MM/dd HH:mm:ss")

        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    }
}
