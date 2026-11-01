package io.github.misdocumeno

import io.github.misdocumeno.application.CheckSession
import io.github.misdocumeno.application.LoginPlayer
import io.github.misdocumeno.application.PlayerSession
import io.github.misdocumeno.application.SessionManager
import io.github.misdocumeno.infra.bukkit.AuthEventListener
import io.github.misdocumeno.infra.bukkit.BlockEventListener
import io.github.misdocumeno.infra.bukkit.BukkitAuthFeedback
import io.github.misdocumeno.infra.bukkit.BukkitAuthTimeout
import io.github.misdocumeno.infra.bukkit.BukkitPlayerBlocker
import io.github.misdocumeno.infra.bukkit.LoginCommand
import io.github.misdocumeno.infra.bukkit.BukkitConfig
import io.github.misdocumeno.infra.http.KtorAuthAction
import io.github.misdocumeno.infra.redis.RedisAuthQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.plugin.java.JavaPlugin
import redis.clients.jedis.JedisPool

class BackendAuthAdapterPlugin : JavaPlugin() {

    private lateinit var scope: CoroutineScope

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val ktor = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        }

        val redis = JedisPool(System.getenv("REDIS_URL"))

        val authQuery = RedisAuthQuery(redis)
        val authAction = KtorAuthAction(ktor)

        val checkSession = CheckSession(authQuery)
        val loginPlayer = LoginPlayer(authAction)

        val config = BukkitConfig(this)

        val blocker = BukkitPlayerBlocker(this)
        val feedback = BukkitAuthFeedback(config)
        val timeout = BukkitAuthTimeout(scope, this, config)

        val sessionManager = SessionManager { PlayerSession(blocker, feedback, timeout) }

        server.pluginManager.registerEvents(
            AuthEventListener(sessionManager, checkSession, scope),
            this,
        )

        server.pluginManager.registerEvents(
            BlockEventListener(sessionManager),
            this,
        )

        getCommand("login")!!.setExecutor(
            LoginCommand(sessionManager, loginPlayer, feedback, scope, this),
        )
    }

    override fun onDisable() {
        scope.cancel()
    }
}