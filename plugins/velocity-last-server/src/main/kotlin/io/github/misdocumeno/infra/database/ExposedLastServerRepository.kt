package io.github.misdocumeno.infra.database

import io.github.misdocumeno.infra.repository.LastServerRepository
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.*
import java.util.UUID

class ExposedLastServerRepository : LastServerRepository {

    override fun save(uuid: UUID, server: String) {
        transaction {
            LastServerTable.insertIgnore {
                it[LastServerTable.uuid] = uuid.toString()
                it[LastServerTable.server] = server
                it[updatedAt] = System.currentTimeMillis()
            }

            LastServerTable.update({ LastServerTable.uuid eq uuid.toString() }) {
                it[LastServerTable.server] = server
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override fun find(uuid: UUID): String? =
        transaction {
            LastServerTable
                .selectAll().where { LastServerTable.uuid eq uuid.toString() }
                .limit(1)
                .map { it[LastServerTable.server] }
                .firstOrNull()
        }

}