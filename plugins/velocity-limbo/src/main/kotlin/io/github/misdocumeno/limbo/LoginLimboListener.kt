package io.github.misdocumeno.limbo

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.Scheduler
import io.github.misdocumeno.domain.LoginApi
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger

/**
 * Sends every login to the limbo instead of a backend.
 *
 * `entryServer` is only the connection request that ends the limbo: the proxy
 * `last-session` plugin rewrites the first connect to the player's last backend,
 * and a player with no history simply lands there (today `main`).
 */
class LoginLimboListener(
    private val proxy: ProxyServer,
    private val scheduler: Scheduler,
    private val plugin: Any,
    private val limbo: Limbo,
    private val api: LoginApi,
    private val entryServer: String,
    private val timeoutSeconds: Long,
    private val logger: Logger,
) {

    private val mm = MiniMessage.miniMessage()

    @Subscribe
    fun onLoginLimboRegister(event: LoginLimboRegisterEvent) {
        val player = event.player
        event.addOnJoinCallback {
            logger.info("sending {} to the login limbo", player.username)
            limbo.spawnPlayer(
                player,
                LoginSessionHandler(
                    player = player,
                    scheduler = scheduler,
                    plugin = plugin,
                    api = api,
                    onAuthenticated = ::connectToBackend,
                    timeoutSeconds = timeoutSeconds,
                ),
            )
        }
    }

    private fun connectToBackend(player: Player) {
        val target = proxy.getServer(entryServer).orElse(null)
        if (target == null) {
            logger.error("entry server '{}' is not registered on the proxy", entryServer)
            player.disconnect(mm.deserialize("<red>No hay servidor disponible. Probá de nuevo.</red>"))
            return
        }
        player.createConnectionRequest(target).connect().whenComplete { result, error ->
            if (error != null || !result.isSuccessful) {
                val reason = error?.message ?: result.status.name
                logger.warn("post-login connect for {} to {} failed: {}", player.username, entryServer, reason)
                player.disconnect(mm.deserialize("<red>No se pudo conectar al servidor. Probá de nuevo.</red>"))
            }
        }
    }
}
