package io.github.misdocumeno.infra.database

import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.util.UUID
import java.util.logging.Logger

/**
 * Reads the durable last-session row written by backend-last-session.
 *
 * Postgres is the truth (it survives months, unlike Redis). The row only tells
 * which backend owns an offline player; inventories themselves are never stored
 * here. A missing DATABASE_URL degrades to "unknown server".
 */
class LastSessionRepository private constructor(
    private val dataSource: PGSimpleDataSource?,
    private val logger: Logger,
) {

    data class Entry(val server: String, val world: String?, val updatedAt: Long)

    fun lookup(uuid: UUID): Entry? {
        val source = dataSource ?: return null
        return runCatching {
            source.connection.use { connection ->
                connection.prepareStatement(
                    "select server, world, updated_at from player_last_session where uuid = ?",
                ).use { statement ->
                    statement.setString(1, uuid.toString())
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) return null
                        Entry(rows.getString(1), rows.getString(2), rows.getLong(3))
                    }
                }
            }
        }.onFailure { logger.warning("last-session lookup failed for $uuid: ${it.message}") }
            .getOrNull()
    }

    companion object {
        fun fromEnv(logger: Logger): LastSessionRepository {
            val raw = System.getenv("DATABASE_URL")
            if (raw.isNullOrBlank()) {
                logger.warning("DATABASE_URL unset; offline owner lookup disabled")
                return LastSessionRepository(null, logger)
            }
            val (jdbcUrl, user, password) = toJdbc(raw)
            val source =
                PGSimpleDataSource().apply {
                    setURL(jdbcUrl)
                    setUser(user)
                    setPassword(password)
                    // A dead DB must not pin an async scheduler thread for a minute.
                    setConnectTimeout(3)
                    setLoginTimeout(3)
                    setSocketTimeout(5000)
                }
            return LastSessionRepository(source, logger)
        }

        /** Accepts `postgres://`/`postgresql://` (compose) or an existing jdbc URL. */
        internal fun toJdbc(databaseUrl: String): Triple<String, String, String> {
            if (databaseUrl.startsWith("jdbc:")) return Triple(databaseUrl, "", "")
            val uri = URI(databaseUrl)
            val userInfo = uri.userInfo?.split(":", limit = 2)
            val user = userInfo?.getOrNull(0).orEmpty()
            val password = userInfo?.getOrNull(1).orEmpty()
            val database = uri.path.trimStart('/')
            val port = if (uri.port == -1) 5432 else uri.port
            return Triple("jdbc:postgresql://${uri.host}:$port/$database", user, password)
        }
    }
}
