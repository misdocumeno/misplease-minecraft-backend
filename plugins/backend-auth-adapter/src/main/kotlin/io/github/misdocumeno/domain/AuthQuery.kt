package io.github.misdocumeno.domain

interface AuthQuery {
    suspend fun hasSession(username: String): Boolean

    /**
     * Correlation id of the login that created the current session, for logs.
     * `null` when the session is gone or predates the id (proxy never cleared it).
     */
    suspend fun sessionLoginId(username: String): String? = null
}
