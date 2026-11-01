package io.github.misdocumeno.domain

import kotlin.uuid.Uuid

interface AuthenticatedRepository {
    fun isAuthenticated(uuid: Uuid): Boolean
}