package io.github.misdocumeno.domain

interface AuthAction {
    suspend fun authenticate(username: String, password: String): Boolean
}