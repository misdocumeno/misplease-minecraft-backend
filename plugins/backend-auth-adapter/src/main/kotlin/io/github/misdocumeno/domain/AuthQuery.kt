package io.github.misdocumeno.domain

interface AuthQuery {
    suspend fun hasSession(username: String): Boolean
}