package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.MuteState
import io.github.misdocumeno.domain.model.RenderedChatMessage
import net.kyori.adventure.text.minimessage.MiniMessage
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Feedback sent to a muted player *instead of* their message. It travels on the normal
 * broadcast with [ChatAudience.SpecificPlayers], so no protocol change is needed.
 *
 * The reason is player-visible text authored by staff through /mute: it is escaped so
 * MiniMessage tags in it stay literal, and line breaks are collapsed so a reason
 * cannot inject extra lines into the chat.
 */
class MuteNotice(
    private val zone: ZoneId = ChatFormatter.DEFAULT_ZONE,
    private val timeFormat: DateTimeFormatter = ChatFormatter.DEFAULT_TIME_FORMAT,
) {
    private val mm = MiniMessage.miniMessage()

    fun render(mute: MuteState): RenderedChatMessage {
        val expiry = when (val endDate = mute.endDate) {
            null -> "<gray>(permanent)</gray>"
            else -> {
                val at = endDate.atZone(zone)
                "<gray>(until ${timeFormat.format(at)} (${zoneLabel(at.offset)}))</gray>"
            }
        }

        val reason = mute.reason
            ?.replace('\r', ' ')
            ?.replace('\n', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { " <gray>Reason:</gray> <white>${mm.escapeTags(it)}</white>" }
            .orEmpty()

        return RenderedChatMessage("<red>You are muted</red> $expiry$reason")
    }
}
