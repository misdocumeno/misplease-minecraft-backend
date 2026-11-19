package io.github.misdocumeno.limbo

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.scheduler.ScheduledTask
import com.velocitypowered.api.scheduler.Scheduler
import io.github.misdocumeno.VelocityLimboPlugin
import io.github.misdocumeno.domain.LoginApi
import io.github.misdocumeno.domain.LoginResult
import kotlinx.coroutines.runBlocking
import net.elytrium.limboapi.api.Limbo
import net.elytrium.limboapi.api.LimboSessionHandler
import net.elytrium.limboapi.api.player.LimboPlayer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One unauthenticated player inside the proxy limbo.
 *
 * Nothing here touches a backend: the player has no Paper player, no inventory
 * and no world state until the password is accepted and they are connected.
 */
class LoginSessionHandler(
    private val player: Player,
    private val scheduler: Scheduler,
    private val plugin: VelocityLimboPlugin,
    private val api: LoginApi,
    private val onAuthenticated: (LimboPlayer) -> Unit,
    private val timeoutSeconds: Long,
) : LimboSessionHandler {

    private val mm = MiniMessage.miniMessage()
    private val done = AtomicBoolean(false)

    /**
     * Floodgate prefixes Bedrock proxy usernames (`.` in `floodgate/config.yml`);
     * the auth service stores the unprefixed name (same strip as velocity-auth-adapter).
     */
    private val authUsername = player.username.removePrefix(FLOODGATE_PREFIX)
    private var attempts = 0
    private var timeoutTask: ScheduledTask? = null
    private var limboPlayer: LimboPlayer? = null

    override fun onSpawn(server: Limbo, limboPlayer: LimboPlayer) {
        this.limboPlayer = limboPlayer
        player.sendMessage(
            mm.deserialize("$PREFIX<gray>Ingresá con <white>/login <contraseña></white>.</gray>"),
        )
        timeoutTask = scheduler
            .buildTask(plugin, Runnable { timedOut() })
            .delay(timeoutSeconds, TimeUnit.SECONDS)
            .schedule()
    }

    override fun onChat(chat: String) {
        if (done.get()) return

        val parts = chat.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || !parts[0].equals("/login", ignoreCase = true)) {
            prompt("Usá <white>/login <contraseña></white> para entrar.")
            return
        }
        if (parts.size < 2) {
            prompt("Falta la contraseña: <white>/login <contraseña></white>")
            return
        }
        if (attempts >= MAX_ATTEMPTS) {
            disconnect("Demasiados intentos. Reconectá para volver a intentar.")
            return
        }
        attempts++

        val password = parts[1]
        // The chat packet arrives on a netty thread; the HTTP call must not block it.
        scheduler.buildTask(plugin, Runnable { authenticate(password) }).schedule()
    }

    override fun onDisconnect() {
        done.set(true)
        timeoutTask?.cancel()
    }

    private fun authenticate(password: String) {
        if (done.get()) return
        when (val result = runBlocking { api.login(authUsername, password) }) {
            LoginResult.SUCCESS -> {
                if (!done.compareAndSet(false, true)) return
                timeoutTask?.cancel()
                player.sendMessage(
                    mm.deserialize("$PREFIX<gray>Sesión iniciada. Conectando…</gray>"),
                )
                val limboPlayer = limboPlayer
                if (limboPlayer != null) {
                    onAuthenticated(limboPlayer)
                } else {
                    disconnect("Sesión iniciada, pero el limbo no está listo. Reconectá.")
                }
            }

            LoginResult.INVALID_CREDENTIALS ->
                prompt("Contraseña incorrecta. Intentos restantes: <white>${MAX_ATTEMPTS - attempts}</white>")

            LoginResult.UNREACHABLE ->
                prompt("El servicio de login no responde. Probá de nuevo en unos segundos.")
        }
    }

    private fun timedOut() {
        if (!done.compareAndSet(false, true)) return
        disconnect("Tiempo agotado. Reconectá e ingresá con <white>/login</white>.")
    }

    private fun prompt(text: String) {
        player.sendMessage(mm.deserialize("$PREFIX<gray>$text</gray>"))
    }

    private fun disconnect(text: String) {
        player.disconnect(mm.deserialize("$PREFIX<red>$text</red>"))
    }

    private companion object {
        /** Shared chat prefix; keep in sync with backend-tp-command lang files. */
        const val PREFIX =
            "<dark_purple><bold>MisPlease</bold></dark_purple><dark_gray><bold> » </bold></dark_gray>"

        const val MAX_ATTEMPTS = 5

        /** Keep in sync with `username-prefix` in `infra/velocity/config/plugins/floodgate/config.yml`. */
        const val FLOODGATE_PREFIX = "."
    }
}
