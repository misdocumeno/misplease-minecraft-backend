package io.github.misdocumeno.application

import io.ktor.util.collections.ConcurrentMap
import java.util.UUID

class SessionManager(private val factory: () -> PlayerSession) {

    private val sessions = ConcurrentMap<UUID, PlayerSession>()

    fun getOrCreate(playerId: UUID): PlayerSession =
        sessions.computeIfAbsent(playerId) { factory() }

    fun remove(playerId: UUID) {
        sessions.remove(playerId)
    }

    fun get(playerId: UUID) = sessions[playerId]
}