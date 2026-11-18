package io.github.misdocumeno.infra.database

import org.jetbrains.exposed.sql.Database
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI

object DatabaseFactory {
    fun initFromEnv() {
        val raw = System.getenv("DATABASE_URL")
            ?: error("DATABASE_URL is required for velocity-social mail")
        val (jdbcUrl, user, password) = toJdbc(raw)
        // Datasource instead of DriverManager: the driver is relocated into this jar,
        // and DriverManager only scans the system classloader.
        val dataSource =
            PGSimpleDataSource().apply {
                setURL(jdbcUrl)
                setUser(user)
                setPassword(password)
            }
        Database.connect(dataSource)
    }

    internal fun toJdbc(databaseUrl: String): Triple<String, String, String> {
        if (databaseUrl.startsWith("jdbc:")) {
            return Triple(databaseUrl, "", "")
        }
        val uri = URI(databaseUrl)
        val userInfo = uri.userInfo?.split(":", limit = 2)
        val user = userInfo?.getOrNull(0).orEmpty()
        val password = userInfo?.getOrNull(1).orEmpty()
        val db = uri.path.trimStart('/')
        val port = if (uri.port == -1) 5432 else uri.port
        return Triple("jdbc:postgresql://${uri.host}:$port/$db", user, password)
    }
}
