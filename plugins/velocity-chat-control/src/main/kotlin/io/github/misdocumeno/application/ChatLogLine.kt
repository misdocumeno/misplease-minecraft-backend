package io.github.misdocumeno.application

/**
 * One console line per chat message, in the same shape Paper uses for local chat:
 * `<name> message`.
 *
 * Console logs are line-oriented: a raw CR or LF inside a chat message would forge an
 * extra log line (and, since the log is the audit trail for moderation, could hide
 * what was really said). They are replaced with spaces and the body is capped so a
 * single message cannot flood the log file.
 */
object ChatLogLine {
    private const val MAX_MESSAGE_LENGTH = 512

    fun of(name: String, message: String): String {
        val flattened = message.replace('\r', ' ').replace('\n', ' ').trim()
        val body = if (flattened.length > MAX_MESSAGE_LENGTH) {
            flattened.take(MAX_MESSAGE_LENGTH) + "..."
        } else {
            flattened
        }
        return "<$name> $body"
    }
}
