package io.github.misdocumeno.domain

import io.github.misdocumeno.infra.repository.LastSessionRepository
import java.util.UUID

class LastSessionService(private val repository: LastSessionRepository) {
    fun getLastServer(uuid: UUID): String? = repository.findServer(uuid)
}
