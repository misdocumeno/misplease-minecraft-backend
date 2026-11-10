package io.github.misdocumeno.infra.database

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class MailRow(
    val id: Long,
    val recipientName: String,
    val senderName: String,
    val body: String,
    val createdAt: Long,
    val readAt: Long?,
)

class MailRepository {
    fun send(recipientName: String, senderName: String, body: String): Long = transaction {
        PlayerMailTable.insert {
            it[PlayerMailTable.recipientName] = recipientName.lowercase()
            it[PlayerMailTable.senderName] = senderName
            it[PlayerMailTable.body] = body.take(512)
            it[createdAt] = System.currentTimeMillis()
            it[readAt] = null
        }[PlayerMailTable.id]
    }

    fun list(recipientName: String, limit: Int = 20): List<MailRow> = transaction {
        PlayerMailTable
            .selectAll()
            .where { PlayerMailTable.recipientName eq recipientName.lowercase() }
            .orderBy(PlayerMailTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                MailRow(
                    id = row[PlayerMailTable.id],
                    recipientName = row[PlayerMailTable.recipientName],
                    senderName = row[PlayerMailTable.senderName],
                    body = row[PlayerMailTable.body],
                    createdAt = row[PlayerMailTable.createdAt],
                    readAt = row[PlayerMailTable.readAt],
                )
            }
    }

    fun unreadCount(recipientName: String): Long = transaction {
        PlayerMailTable
            .selectAll()
            .where {
                (PlayerMailTable.recipientName eq recipientName.lowercase()) and
                    PlayerMailTable.readAt.isNull()
            }
            .count()
    }

    fun markAllRead(recipientName: String) = transaction {
        PlayerMailTable.update({
            (PlayerMailTable.recipientName eq recipientName.lowercase()) and
                PlayerMailTable.readAt.isNull()
        }) {
            it[readAt] = System.currentTimeMillis()
        }
    }

    fun clearAll(recipientName: String): Int = transaction {
        PlayerMailTable.deleteWhere { PlayerMailTable.recipientName eq recipientName.lowercase() }
    }

    fun clearOne(recipientName: String, id: Long): Boolean = transaction {
        PlayerMailTable.deleteWhere {
            (PlayerMailTable.recipientName eq recipientName.lowercase()) and
                (PlayerMailTable.id eq id)
        } > 0
    }
}
