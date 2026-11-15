package io.github.misdocumeno

import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import io.github.misdocumeno.application.SessionInvalidator
import io.github.misdocumeno.application.UsernameCaseChecker
import io.github.misdocumeno.domain.AuthApi
import io.github.misdocumeno.domain.UsernameCaseCheckResult
import io.github.misdocumeno.infra.http.KtorAuthApi
import io.github.misdocumeno.infra.redis.RedisAuthSet
import io.github.misdocumeno.infra.velocity.UnauthCommandGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.floodgate.api.player.FloodgatePlayer
import org.slf4j.Logger
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Proxy is the truth for "is this player connected to the network".
 *
 * Velocity events:
 * - LoginEvent / DisconnectEvent: real proxy join/leave (NOT fired when moving main↔tech)
 * - ServerConnected / KickedFromServer: backend switches only — do NOT touch auth sessions
 *
 * Redis AUTH_SET is owned by the auth service. This plugin only calls auth HTTP:
 * - LoginEvent: invalidate (drop any stale session before backends check Redis)
 * - DisconnectEvent: invalidate (quit / timeout / proxy kick — every leave path)
 * - Proxy boot/shutdown: clear-all
 * - Reconcile: list authenticated, invalidate anyone not on the proxy
 *
 * Bedrock (Floodgate): strip username prefix, resolve auth UUID, persist own-linking
 * (sqlite via Floodgate-misplease). Do NOT rewrite GameProfile — Floodgate must own the
 * linked UUID for this session or plugin messages kick with "Only Floodgate players…".
 * First link → kick "reconnect" so the next handshake loads LinkedPlayer.
 *
 * Backends are the ones that *read* Redis on join to decide /login.
 */
@Plugin(
    id = "auth-adapter",
    name = "AuthAdapter",
    version = "0.1.0",
    description = "External auth service adapter",
    authors = ["Mis"],
    dependencies = [Dependency(id = "floodgate", optional = true)],
)
class VelocityAuthAdapterPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {

    private lateinit var caseChecker: UsernameCaseChecker
    private lateinit var invalidator: SessionInvalidator
    private lateinit var api: AuthApi
    private var redis: RedisClient? = null
    private var redisConnection: StatefulRedisConnection<String, String>? = null

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }

        api = KtorAuthApi(client)
        caseChecker = UsernameCaseChecker(api)
        invalidator = SessionInvalidator(api)

        System.getenv("REDIS_URL")?.takeIf { it.isNotBlank() }?.let { url ->
            try {
                val redisClient = RedisClient.create(url)
                redis = redisClient
                val conn = redisClient.connect()
                redisConnection = conn
                proxy.eventManager.register(this, UnauthCommandGate(RedisAuthSet(conn)))
            } catch (e: Exception) {
                logger.warn("redis unavailable for unauth command gate: {}", e.message)
            }
        }

        runBlocking {
            try {
                api.clearAllSessions()
                logger.info("cleared auth sessions on proxy start")
            } catch (e: Exception) {
                logger.warn("clear-all on proxy start failed: {}", e.message)
            }
        }

        proxy.scheduler
            .buildTask(this, java.lang.Runnable { reconcileOrphans() })
            .repeat(15, TimeUnit.SECONDS)
            .schedule()
    }

    @Subscribe(order = PostOrder.LAST)
    fun onPreLogin(event: PreLoginEvent) = runBlocking {
        val fg = findFloodgatePlayer(event.username)
        val lookup = authLookupName(event.username, fg)

        when (val result = caseChecker.check(lookup)) {
            UsernameCaseCheckResult.NotFound ->
                event.result = PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Register on misplease.com\n(tu nombre es: $lookup)"),
                )

            is UsernameCaseCheckResult.WrongCase -> {
                if (fg == null) {
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(
                        Component.text("Please login with ${result.correctUsername}"),
                    )
                    return@runBlocking
                }
                if (denyIfAlreadyOnline(event, result.correctUsername)) return@runBlocking
                ensureFloodgateLinkedOrKick(event, fg, result.uuid, result.correctUsername)
            }

            is UsernameCaseCheckResult.ExactMatch -> {
                if (denyIfAlreadyOnline(event, result.username)) return@runBlocking
                if (fg != null) {
                    ensureFloodgateLinkedOrKick(event, fg, result.uuid, result.username)
                }
            }
        }
    }

    @Subscribe
    fun onLogin(event: LoginEvent) {
        // Proxy join (not a backend switch). Belts-and-suspenders against stale Redis
        // after a crash where DisconnectEvent never ran.
        dropSession(event.player.username, "proxy-login")
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        dropSession(event.player.username, "proxy-disconnect")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        runBlocking {
            try {
                api.clearAllSessions()
            } catch (e: Exception) {
                logger.warn("clear-all on proxy shutdown failed: {}", e.message)
            }
        }
        redisConnection?.close()
        redis?.shutdown()
    }

    private fun denyIfAlreadyOnline(event: PreLoginEvent, username: String): Boolean {
        if (proxy.getPlayer(username).isPresent) {
            event.result = PreLoginEvent.PreLoginComponentResult.denied(
                Component.text("That account is already online"),
            )
            return true
        }
        return false
    }

    /**
     * Floodgate only applies LinkedPlayer during handshake. Linking mid-login leaves
     * FloodgatePlayer unlinked while Velocity may already use the Java UUID → kick.
     * If this session already has the link in-memory, allow; otherwise persist + reconnect.
     */
    private fun ensureFloodgateLinkedOrKick(
        event: PreLoginEvent,
        fg: FloodgatePlayer,
        authUuid: String,
        authUsername: String,
    ) {
        val javaId = UUID.fromString(authUuid)
        if (fg.isLinked && fg.correctUniqueId == javaId) {
            return
        }

        if (!persistFloodgateLink(fg, javaId, authUsername)) {
            event.result = PreLoginEvent.PreLoginComponentResult.denied(
                Component.text("No se pudo vincular la cuenta Bedrock. Probá de nuevo."),
            )
            return
        }

        // Link is in sqlite; this FloodgatePlayer still has linkedPlayer=null until re-handshake.
        event.result = PreLoginEvent.PreLoginComponentResult.denied(
            Component.text(
                "¡Tu cuenta de Bedrock quedó vinculada a $authUsername!\nReconectá para empezar a jugar.",
            ),
        )
        logger.info(
            "bedrock link ready for {} ({}); kicked to re-handshake",
            authUsername,
            javaId,
        )
    }

    private fun persistFloodgateLink(fg: FloodgatePlayer, javaId: UUID, authUsername: String): Boolean {
        return try {
            val link = FloodgateApi.getInstance().playerLink
            if (link == null || !link.isEnabled) {
                logger.warn("floodgate player-link disabled; cannot link {}", authUsername)
                return false
            }
            val bedrockId = fg.javaUniqueId
            val existing = link.getLinkedPlayer(bedrockId).join()
            if (existing != null && existing.javaUniqueId == javaId) {
                return true
            }
            link.linkPlayer(bedrockId, javaId, authUsername).join()
            logger.info("floodgate linked {} -> {} ({})", bedrockId, authUsername, javaId)
            true
        } catch (e: Exception) {
            logger.warn("floodgate linkPlayer failed for {}: {}", authUsername, e.message)
            false
        }
    }

    private fun authLookupName(username: String, fg: FloodgatePlayer?): String {
        if (fg != null) {
            if (fg.isLinked) return fg.correctUsername
            return stripFloodgatePrefix(fg.javaUsername)
        }
        return stripFloodgatePrefix(username)
    }

    private fun stripFloodgatePrefix(name: String): String =
        if (name.startsWith(FLOODGATE_PREFIX)) name.substring(FLOODGATE_PREFIX.length) else name

    private fun findFloodgatePlayer(username: String): FloodgatePlayer? {
        return try {
            FloodgateApi.getInstance().players.firstOrNull { p ->
                p.javaUsername.equals(username, ignoreCase = true) ||
                    p.correctUsername.equals(username, ignoreCase = true) ||
                    stripFloodgatePrefix(p.javaUsername).equals(username, ignoreCase = true)
            }
        } catch (e: Throwable) {
            logger.debug("floodgate player lookup skipped: {}", e.message)
            null
        }
    }

    private fun dropSession(username: String, reason: String) {
        runBlocking {
            try {
                invalidator.invalidate(username)
            } catch (e: Exception) {
                logger.warn("invalidate {} ({}): {}", username, reason, e.message)
            }
        }
    }

    private fun reconcileOrphans() {
        runBlocking {
            try {
                val authed = api.listAuthenticated()
                val online = proxy.allPlayers.mapTo(HashSet()) { it.username }
                for (name in authed) {
                    if (name !in online) {
                        invalidator.invalidate(name)
                        logger.info("reconcile invalidated orphan session {}", name)
                    }
                }
            } catch (e: Exception) {
                logger.warn("auth session reconcile failed: {}", e.message)
            }
        }
    }

    companion object {
        /** Keep in sync with floodgate username-prefix on the proxy. */
        private const val FLOODGATE_PREFIX = "."
    }
}
