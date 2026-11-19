package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.CheckSession
import io.github.misdocumeno.application.SessionEvent
import io.github.misdocumeno.application.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.logging.Logger

class AuthEventListener(
    private val sessionManager: SessionManager,
    private val checkSession: CheckSession,
    private val scope: CoroutineScope,
    private val logger: Logger,
) : Listener {

    @EventHandler
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        val session = sessionManager.getOrCreate(event.uniqueId)
        session.dispatch(SessionEvent.PreLoginStarted)
        scope.launch {
            val hasSession = checkSession.execute(event.name)
            // Same id the proxy limbo logged for the `/login` that created the
            // session: authenticated=false with a loginId means something dropped
            // it after login; authenticated=false with no id means the join never
            // went through the limbo.
            val loginId = checkSession.loginId(event.name)
            logger.info(
                "session check user=${event.name} authenticated=$hasSession " +
                    "loginId=${loginId ?: "-"}",
            )
            if (!hasSession) {
                logger.warning(
                    "user=${event.name} reached backend without a session " +
                        "(loginId=${loginId ?: "-"}) - asking for /login again",
                )
            }
            session.dispatch(SessionEvent.AuthResult(hasSession))
        }
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
}