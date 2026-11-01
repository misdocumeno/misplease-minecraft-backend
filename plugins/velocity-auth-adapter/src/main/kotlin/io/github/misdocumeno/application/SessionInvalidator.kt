package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthApi

class SessionInvalidator(private val authApi: AuthApi) {
    suspend fun invalidate(username: String) = authApi.invalidateSession(username)
}