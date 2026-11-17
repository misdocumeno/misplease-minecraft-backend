package io.github.misdocumeno.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatLogLineTest {

    @Test
    fun `plain message keeps the paper-shaped line`() {
        assertEquals("<Steve> hola", ChatLogLine.of("Steve", "hola"))
    }

    @Test
    fun `line breaks cannot forge extra log lines`() {
        // A chat body with CR/LF would otherwise produce a second, fake console line -
        // the log is the moderation audit trail.
        val line = ChatLogLine.of("Steve", "hola\r\n[12:00:00] [Server thread/INFO]: Steve issued server command: /op Steve\n")

        assertFalse(line.contains('\n'), "no newline may survive: $line")
        assertFalse(line.contains('\r'), "no carriage return may survive: $line")
        assertTrue(line.startsWith("<Steve> hola"), "content must still be readable: $line")
    }

    @Test
    fun `very long messages are truncated`() {
        val line = ChatLogLine.of("Steve", "x".repeat(5000))

        assertTrue(line.length < 600, "the log line must stay bounded: ${line.length}")
        assertTrue(line.endsWith("..."), "truncation must be visible: ${line.takeLast(10)}")
    }
}
