package io.github.misdocumeno.infra.database

import org.jetbrains.exposed.sql.Table

object LastServerTable : Table("player_last_server") {
    val uuid = varchar("uuid", 36)
    val server = varchar("server", 128)
    val updatedAt = long("updated_at")

    override val primaryKey: PrimaryKey
        get() = PrimaryKey(uuid)
}