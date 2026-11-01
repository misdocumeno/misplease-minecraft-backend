package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthQuery

class CheckSession(private val authQuery: AuthQuery) {
    suspend fun execute(username: String) = authQuery.hasSession(username)
}