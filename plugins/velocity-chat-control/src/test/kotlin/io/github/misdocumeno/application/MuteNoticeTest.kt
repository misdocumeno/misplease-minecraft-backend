package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.MuteState
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuteNoticeTest {

    private val notice = MuteNotice(
        ZoneId.of("America/Argentina/Buenos_Aires"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
    )

    private fun plain(mute: MuteState): String =
        PlainTextComponentSerializer.plainText().serialize(
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(notice.render(mute).miniMessage),
        )

    @Test
    fun `permanent mute says so instead of printing the max instant`() {
        val text = plain(MuteState(reason = null, endDate = null))

        assertTrue(text.contains("You are muted"), text)
        assertTrue(text.contains("permanent"), text)
        assertFalse(text.contains("9999"), "no sentinel date may leak: $text")
    }

    @Test
    fun `temporary mute shows the local end time with its offset`() {
        val mute = MuteState(
            reason = "spam",
            endDate = Instant.parse("2026-11-18T13:00:00Z"),
        )

        val text = plain(mute)

        assertTrue(text.contains("2026/11/18 10:00:00 (UTC-3)"), text)
        assertTrue(text.contains("spam"), text)
    }

    @Test
    fun `reason stays literal text and cannot add lines`() {
        val mute = MuteState(
            reason = "<red>fake\nInjected line</red>",
            endDate = null,
        )

        val text = plain(mute)

        assertTrue(text.contains("<red>fake"), "tags must stay literal: $text")
        assertFalse(text.contains('\n'), "the reason must not inject a line break: $text")
    }
}
