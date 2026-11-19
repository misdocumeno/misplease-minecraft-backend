package io.github.misdocumeno

import io.github.misdocumeno.application.CheckSession
import io.github.misdocumeno.application.PlayerSession
import io.github.misdocumeno.application.SessionEvent
import io.github.misdocumeno.application.SessionManager
import io.github.misdocumeno.infra.bukkit.AuthEventListener
import io.github.misdocumeno.infra.bukkit.BlockEventListener
import io.github.misdocumeno.infra.bukkit.BukkitAuthFeedback
import io.github.misdocumeno.infra.bukkit.BukkitAuthTimeout
import io.github.misdocumeno.infra.bukkit.BukkitPlayerBlocker
import io.github.misdocumeno.infra.bukkit.BukkitConfig
import io.github.misdocumeno.infra.log.LoginCommandLogFilter
import io.github.misdocumeno.infra.redis.RedisAuthQuery
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.plugin.java.JavaPlugin

class BackendAuthAdapterPlugin : JavaPlugin() {

    private lateinit var scope: CoroutineScope
    private lateinit var redis: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // After Done (: delay≥2 is not run in Paper's pre-Done delayed-init heartbeat.
        // Installing the Log4j filter during onEnable hangs boot (see LoginCommandLogFilter).
        server.scheduler.runTaskLater(this, Runnable {
            val where = LoginCommandLogFilter.install()
            if (where == null) {
                logger.warning(
                    "login log filter NOT installed (Log4j context unreachable): " +
                        "/login passwords will appear in logs/latest.log",
                )
            } else {
                logger.info(
                    "login log filter installed on ${where.loggerConfigs} logger configs " +
                        "and ${where.appenders} appenders",
                )
            }
        }, 20L)

        redis = RedisClient.create(System.getenv("REDIS_URL"))
        connection = redis.connect()

        val authQuery = RedisAuthQuery(connection)

        val checkSession = CheckSession(authQuery)

        val config = BukkitConfig(this)

        val blocker = BukkitPlayerBlocker(this)
        val feedback = BukkitAuthFeedback(config)
        val timeout = BukkitAuthTimeout(scope, this, config)

        val sessionManager = SessionManager { PlayerSession(blocker, feedback, timeout) }

        server.pluginManager.registerEvents(
            AuthEventListener(sessionManager, checkSession, logger),
            this,
        )

        server.pluginManager.registerEvents(
            BlockEventListener(sessionManager),
            this,
        )

        // The backend guard is onPreLogin only. There is no re-prompt here: a
        // session cleared mid-play is handled by the proxy (DisconnectEvent) and
        // by the next join, which goes through the limbo again.
    }

    override fun onDisable() {
        scope.cancel()
        if (::connection.isInitialized) connection.close()
        if (::redis.isInitialized) redis.shutdown()
    }
}