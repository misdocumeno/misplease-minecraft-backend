package io.github.misdocumeno.infra.repository

import java.util.UUID

interface LastSessionRepository {
    fun findServer(uuid: UUID): String?
}
