package io.github.misdocumeno.infra.repository

import java.util.UUID

interface LastServerRepository {
    fun save(uuid: UUID, server: String)
    fun find(uuid: UUID): String?
}