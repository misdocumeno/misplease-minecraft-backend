package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.GlobalLocation

interface GlobalLocationResolver {
    suspend fun getPlayerLocation(username: String): GlobalLocation?
}