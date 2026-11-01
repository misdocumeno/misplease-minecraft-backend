package io.github.misdocumeno.infra.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path

object DatabaseFactory {

    fun init(dataDir: Path) {
        val dbFile = dataDir.resolve("data.db").toAbsolutePath().toString()

        Database.connect(
            url = "jdbc:sqlite:$dbFile",
            driver = "org.sqlite.JDBC"
        )

        transaction {
            SchemaUtils.create(LastServerTable)
        }
    }
}