package io.github.misdocumeno.application

interface UsernameRepository {

    fun getAllNames(): List<String>

    fun findCaseInsensitive(username: String): String?

    fun findPartial(partial: String): String?
    fun findPartialMultiple(partial: String): List<String>

    /** Tab-complete: prefix match (not substring). */
    fun suggest(partial: String): List<String> =
        getAllNames()
            .filter { it.startsWith(partial, ignoreCase = true) }
            .sortedBy { it.lowercase() }
}
