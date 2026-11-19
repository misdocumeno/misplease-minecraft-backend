package io.github.misdocumeno.domain

interface AuthApi {
    suspend fun checkUsernameCase(username: String): UsernameCaseCheckResult

    /**
     * [source] and [requestId] are only for the auth service log: they say who
     * dropped the session (`proxy-disconnect`, `proxy-reconcile`, ...) and let the
     * line be tied to the proxy line that caused it. Never credentials.
     */
    suspend fun invalidateSession(username: String, source: String, requestId: String)
    suspend fun clearAllSessions(source: String, requestId: String)
    suspend fun listAuthenticated(): List<String>
}
