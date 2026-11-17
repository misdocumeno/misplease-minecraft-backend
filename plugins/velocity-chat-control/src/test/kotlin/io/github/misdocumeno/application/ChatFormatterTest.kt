package io.github.misdocumeno.application

import io.github.misdocumeno.domain.UsernameRepository
import io.github.misdocumeno.domain.model.ChatIntent
import net.kyori.adventure.text.minimessage.MiniMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ChatFormatterTest {

    private val noNames = object : UsernameRepository {
        override fun getUsername(uuid: Uuid): String? = null
    }

    private fun formatter() = ChatFormatter(
        noNames,
        ZoneId.of("America/Argentina/Buenos_Aires"),
        DateTimeFormatter.ofPattern("dd/MM HH:mm"),
    )

    private fun intent(raw: String, at: String, name: String? = "<gold>Steve</gold>") = ChatIntent(
        messageId = Uuid.random(),
        senderUUID = Uuid.random(),
        rawMessage = raw,
        timestamp = Instant.parse(at).toEpochMilli(),
        senderNameMiniMessage = name,
    )

    private fun line(raw: String, at: String): String =
        formatter().format(intent(raw, at)).miniMessage

    @Test
    fun `hover shows the event time in the configured zone`() {
        // 2026-11-17T23:34Z is 20:34 in Buenos Aires (UTC-3): the hover must use the
        // event instant, not the container clock (UTC).
        val rendered = MiniMessage.miniMessage().deserialize(line("hola", "2026-11-17T23:34:00Z"))

        assertNotNull(rendered.hoverEvent(), "the chat line must carry a hover event")

        // Round-trip: the time must live in the hover argument, not in the body text.
        val back = MiniMessage.miniMessage().serialize(rendered)
        assertTrue(back.startsWith("<hover:"), "hover must wrap the whole line: $back")
        assertTrue(back.contains("17/11 20:34"), "hover must show the local event time: $back")
    }

    @Test
    fun `wrapping the line in a hover keeps name colors and escapes chat tags`() {
        val out = line("<red>not a tag", "2026-11-17T23:34:00Z")

        assertTrue(out.contains("<gold>Steve</gold>"), "sender name must keep its LP colors: $out")
        assertTrue(out.contains("\\<red>not a tag"), "chat tags must stay literal: $out")
        assertTrue(out.startsWith("<hover:show_text:'"), "hover must wrap the whole line: $out")
        assertTrue(out.endsWith("</hover>"), "hover must be closed: $out")
    }
}
