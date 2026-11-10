package io.github.misdocumeno.infra.database

import org.jetbrains.exposed.sql.Table

object PlayerMailTable : Table("player_mail") {
    val id = long("id").autoIncrement()
    val recipientName = varchar("recipient_name", 16)
    val senderName = varchar("sender_name", 16)
    val body = varchar("body", 512)
    val createdAt = long("created_at")
    val readAt = long("read_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
