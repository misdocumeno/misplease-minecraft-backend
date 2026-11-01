package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthApi

class UsernameCaseChecker(private val authApi: AuthApi) {
    suspend fun check(username: String) = authApi.checkUsernameCase(username)
}