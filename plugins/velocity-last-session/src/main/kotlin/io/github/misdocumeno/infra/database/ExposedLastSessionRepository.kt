package io.github.misdocumeno.infra.database

import io.github.misdocumeno.infra.repository.LastSessionRepository
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedLastSessionRepository : LastSessionRepository {
    override fun findServer(uuid: UUID): String? =
        transaction {
            LastSessionTable
                .selectAll()
                .where { LastSessionTable.uuid eq uuid.toString() }
                .limit(1)
                .map { it[LastSessionTable.server] }
                .firstOrNull()
        }
}
