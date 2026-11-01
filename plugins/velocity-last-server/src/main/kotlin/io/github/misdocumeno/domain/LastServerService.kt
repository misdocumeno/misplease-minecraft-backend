package io.github.misdocumeno.domain

import io.github.misdocumeno.infra.repository.LastServerRepository
import java.util.UUID

class LastServerService(private val repository: LastServerRepository) {

    fun record(uuid: UUID, server: String) {
        repository.save(uuid, server)
    }

    fun getLastServer(uuid: UUID): String? = repository.find(uuid)
}