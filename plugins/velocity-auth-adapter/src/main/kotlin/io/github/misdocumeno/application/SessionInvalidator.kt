package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthApi

class SessionInvalidator(private val authApi: AuthApi) {
    suspend fun invalidate(username: String, source: String, requestId: String) =
        authApi.invalidateSession(username, source, requestId)

    suspend fun clearAll(source: String, requestId: String) =
        authApi.clearAllSessions(source, requestId)
}
