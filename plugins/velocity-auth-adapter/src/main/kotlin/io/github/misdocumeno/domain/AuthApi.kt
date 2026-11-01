package io.github.misdocumeno.domain

interface AuthApi {
    suspend fun checkUsernameCase(username: String): UsernameCaseCheckResult
    suspend fun invalidateSession(username: String)
}