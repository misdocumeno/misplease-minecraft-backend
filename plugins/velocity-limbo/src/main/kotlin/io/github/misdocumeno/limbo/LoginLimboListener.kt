package io.github.misdocumeno.limbo

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.Scheduler
import io.github.misdocumeno.VelocityLimboPlugin
import io.github.misdocumeno.domain.LoginApi
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent
import net.elytrium.limboapi.api.player.LimboPlayer
import org.slf4j.Logger

/**
 * Sends every login to the limbo instead of a backend.
 *
 * LimboAPI holds the proxy's own login until [LimboPlayer.disconnect] is called:
 * that resumes the queued connection to the server Velocity picked from `try`,
 * re-firing `GameProfileRequestEvent`/`LoginEvent`. `velocity-last-session` then
 * rewrites that first connect (`previousServer == null`) to the last backend.
 * Do not call `createConnectionRequest` from inside the limbo - it bypasses the
 * queue and the login/client state it left behind.
 */
class LoginLimboListener(
    private val scheduler: Scheduler,
    private val plugin: VelocityLimboPlugin,
    private val limbo: Limbo,
    private val api: LoginApi,
    private val timeoutSeconds: Long,
    private val logger: Logger,
) {

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
                    onAuthenticated = { limboPlayer ->
                        logger.info("{} authenticated, leaving the login limbo", player.username)
                        limboPlayer.disconnect()
                    },
                    timeoutSeconds = timeoutSeconds,
                ),
            )
        }
    }
}
