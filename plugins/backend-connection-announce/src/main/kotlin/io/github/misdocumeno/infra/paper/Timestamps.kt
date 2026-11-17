package io.github.misdocumeno.infra.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import java.time.Instant
import java.time.ZoneId
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
 * UTC) and `MISPLEASE_TIME_FORMAT` (default dd/MM HH:mm).
 */
object Timestamps {

    private val zone: ZoneId =
        env("MISPLEASE_TIME_ZONE")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.of("America/Argentina/Buenos_Aires")

    private val format: DateTimeFormatter =
        DateTimeFormatter.ofPattern(env("MISPLEASE_TIME_FORMAT") ?: "dd/MM HH:mm")

    fun hover(at: Instant = Instant.now()): HoverEvent<Component> =
        Component.text(format.format(at.atZone(zone))).asHoverEvent()

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}
