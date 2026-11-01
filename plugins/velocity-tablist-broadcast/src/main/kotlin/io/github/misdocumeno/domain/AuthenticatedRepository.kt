package io.github.misdocumeno.domain

interface AuthenticatedRepository {
    fun isAuthenticated(username: String): Boolean
}