package io.github.misdocumeno.domain

import kotlin.uuid.Uuid

interface UsernameRepository {
    fun getUsername(uuid: Uuid): String?
}