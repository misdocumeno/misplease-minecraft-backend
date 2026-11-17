package io.github.misdocumeno.infra.log

import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.ParameterizedMessage
import org.apache.logging.log4j.message.SimpleMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the /login redaction.
 *
 * Paper logs `LOGGER.info("{} issued server command: {}", name, command)`; the first
 * version of this filter only looked at `Message.format`, where the command is always
 * `{}`, so it never matched. These cases pin the shapes that matter.
 */
class LoginCommandLogFilterTest {

    private fun parameterized(name: String, command: String) = Log4jLogEvent.newBuilder()
        .setMessage(ParameterizedMessage("{} issued server command: {}", name, command))
        .build()

    private fun assertResult(expected: Filter.Result, event: org.apache.logging.log4j.core.LogEvent) {
        assertEquals(expected, LoginCommandLogFilter.filter(event))
    }

    @Test
    fun `drops the paper-shaped login and register lines`() {
        assertResult(Filter.Result.DENY, parameterized("Steve", "/login hunter2"))
        assertResult(Filter.Result.DENY, parameterized("Steve", "/register hunter2"))
        assertResult(Filter.Result.DENY, parameterized("Steve", "/LOGIN hunter2"))
        assertResult(
            Filter.Result.DENY,
            Log4jLogEvent.newBuilder()
                .setMessage(SimpleMessage("Steve issued server command: /login hunter2"))
                .build(),
        )
    }

    @Test
    fun `keeps every other line`() {
        assertResult(Filter.Result.NEUTRAL, parameterized("Steve", "/home"))
        assertResult(Filter.Result.NEUTRAL, parameterized("Steve", "/loginfoo"))
        // Chat that merely mentions the command is not a command line.
        assertResult(
            Filter.Result.NEUTRAL,
            Log4jLogEvent.newBuilder()
                .setMessage(ParameterizedMessage("<{}> {}", "Steve", "how do I /login again?"))
                .build(),
        )
        assertResult(Filter.Result.NEUTRAL, Log4jLogEvent.newBuilder().build())
    }
}
