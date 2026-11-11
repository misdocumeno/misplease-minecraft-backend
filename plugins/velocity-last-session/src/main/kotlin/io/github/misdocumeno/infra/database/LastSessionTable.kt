package io.github.misdocumeno.infra.database

import org.jetbrains.exposed.sql.Table

/** Schema owned by packages/db (Drizzle); plugins must not create/alter tables. */
object LastSessionTable : Table("player_last_session") {
    val uuid = varchar("uuid", 36)
    val server = varchar("server", 128)
    val world = varchar("world", 128).nullable()
    val x = double("x").nullable()
    val y = double("y").nullable()
    val z = double("z").nullable()
    val yaw = float("yaw").nullable()
    val pitch = float("pitch").nullable()
    val updatedAt = long("updated_at")

    override val primaryKey: PrimaryKey
        get() = PrimaryKey(uuid)
}
