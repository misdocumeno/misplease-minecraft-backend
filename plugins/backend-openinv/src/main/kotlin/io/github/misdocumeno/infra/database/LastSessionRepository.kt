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

    data class KnownPlayer(val uuid: UUID, val name: String, val updatedAt: Long)

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

    /**
     * Every player this network knows, most recently seen first. The session
     * table only has the players who played since it was introduced, so it is
     * unioned with LuckPerms (the registration list) and sorted by the session
     * timestamp. Used for name resolution and tab completion; blocking, call
     * from the profile executor.
     */
    fun players(): List<KnownPlayer> {
        val source = dataSource ?: return emptyList()
        return runCatching {
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(PLAYERS_SQL).use { rows ->
                        buildList {
                            while (rows.next()) {
                                val uuid = runCatching { UUID.fromString(rows.getString(1)) }.getOrNull()
                                    ?: continue
                                val name = rows.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                                add(KnownPlayer(uuid, name, rows.getLong(3)))
                            }
                        }
                    }
                }
            }
        }.onFailure { logger.warning("player directory lookup failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Case-insensitive lookup of a registered name, so `/openinv masterangel` works too. */
    fun findByName(name: String): KnownPlayer? {
        val source = dataSource ?: return null
        return runCatching {
            source.connection.use { connection ->
                connection.prepareStatement(BY_NAME_SQL).use { statement ->
                    statement.setString(1, name)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) return null
                        val uuid = runCatching { UUID.fromString(rows.getString(1)) }.getOrNull()
                            ?: return null
                        val resolved = rows.getString(2)?.takeIf { it.isNotBlank() } ?: return null
                        KnownPlayer(uuid, resolved, 0L)
                    }
                }
            }
        }.onFailure { logger.warning("player lookup failed for $name: ${it.message}") }
            .getOrNull()
    }

    companion object {
        // LuckPerms is the registration list (a player row exists after the first
        // login). It is third-party data, read-only here; the join is only for
        // names, never for ownership. `updated_at` orders both halves.
        private const val PLAYERS_SQL = """
            select x.uuid, x.username, x.updated_at
            from (
                select s.uuid, l.username, s.updated_at
                from player_last_session s
                left join luckperms_players l on l.uuid = s.uuid
                union all
                select l.uuid, l.username, 0
                from luckperms_players l
                where not exists (select 1 from player_last_session s where s.uuid = l.uuid)
            ) x
            where x.username is not null and x.username <> ''
            order by x.updated_at desc, x.username asc
        """

        private const val BY_NAME_SQL =
            "select uuid, username from luckperms_players where lower(username) = lower(?) limit 1"

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
