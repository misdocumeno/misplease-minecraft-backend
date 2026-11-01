package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthAction

class LoginPlayer(private val authAction: AuthAction) {
    suspend fun execute(username: String, password: String) =
        authAction.authenticate(username, password)
}