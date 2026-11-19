package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.CheckSession
import io.github.misdocumeno.application.SessionEvent
import io.github.misdocumeno.application.SessionManager
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.logging.Logger

/**
 * Backends no longer own login: `/login` happens in the proxy limbo, which creates
 * the Redis session. This is the guard, not a login surface. If the session is
 * missing the player is sent back to reconnect (and therefore to the limbo)
 * instead of being asked for a second `/login` here.
 */
class AuthEventListener(
    private val sessionManager: SessionManager,
    private val checkSession: CheckSession,
    private val logger: Logger,
) : Listener {

    @EventHandler
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        // AsyncPreLogin is already off the main thread; Redis must answer before
        // the join is allowed, otherwise the player lands unauthenticated.
        val hasSession = runBlocking { checkSession.execute(event.name) }
        // Same id the proxy limbo logged for the `/login` that created the session.
        val loginId = runBlocking { checkSession.loginId(event.name) }
        logger.info(
            "session check user=${event.name} authenticated=$hasSession " +
                "loginId=${loginId ?: "-"}",
        )
        if (!hasSession) {
            logger.warning(
                "user=${event.name} reached backend without a session " +
                    "(loginId=${loginId ?: "-"}) - kicking to reconnect",
            )
            sessionManager.remove(event.uniqueId)
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, NO_SESSION)
            return
        }
        sessionManager.getOrCreate(event.uniqueId)
            .dispatch(SessionEvent.AuthResult(true))
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val session = sessionManager.getOrCreate(event.player.uniqueId)
        session.dispatch(SessionEvent.PlayerJoined(BukkitPlayerHandle(event.player)))
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val session = sessionManager.get(event.player.uniqueId)
        session?.dispatch(SessionEvent.PlayerLeft)
        sessionManager.remove(event.player.uniqueId)
    }

    private companion object {
        val NO_SESSION: Component = Component.text(
            "Tu sesión de MisPlease no está activa.\n" +
                "Reconectá para iniciar sesión en el lobby.",
        )
    }
}
