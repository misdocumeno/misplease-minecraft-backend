package io.github.misdocumeno.domain

/**
 * Nickname rules shared by the proxy command and its tests.
 *
 * A nickname is stored in LuckPerms meta as legacy `&` codes so every platform can
 * read it with the same serializer. Players without colour permission get the
 * codes stripped instead of rejected: the rest of the name is still useful, and
 * silently dropping the whole nickname would look like the command failed.
 * Length and characters mirror the old Essentials limit (15 visible characters,
 * `[A-Za-z0-9_]`) so a nickname cannot smuggle spaces or MiniMessage tags into a
 * chat line.
 */
object Nicknames {
    const val META_KEY = "nickname"
    const val MAX_VISIBLE = 15
    const val PREFIX = "~"

    private val VISIBLE_PATTERN = Regex("^[A-Za-z0-9_]+$")
    private val CODE = Regex("[&§][0-9a-fk-orA-FK-OR]")
    private val INCOMPLETE_CODE = Regex("[&§](?![0-9a-fk-orA-FK-OR])")
    private val CLEAR_WORDS = setOf("off", "reset", "clear", "none", "-")

    fun isClear(raw: String): Boolean = raw.lowercase() in CLEAR_WORDS

    /** Returns the LuckyPerms meta value, or null when the name is not valid. */
    fun normalize(raw: String, allowColour: Boolean): String? {
        var value = raw.trim().replace('§', '&')
        if (value.isEmpty()) return null
        value = if (allowColour) {
            // Keep only complete codes, so a stray `&` cannot eat the next letter.
            value.replace(INCOMPLETE_CODE, "")
        } else {
            value.replace(CODE, "")
        }
        val visible = value.replace(CODE, "")
        if (visible.isEmpty() || visible.length > MAX_VISIBLE) return null
        if (!visible.matches(VISIBLE_PATTERN)) return null
        return value
    }

    /** What chat and the tab list render: `~` marks the name as a nickname. */
    fun display(value: String): String = PREFIX + value
}
