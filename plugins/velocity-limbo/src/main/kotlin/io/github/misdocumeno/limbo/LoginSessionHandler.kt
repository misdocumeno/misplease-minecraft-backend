package io.github.misdocumeno.limbo

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.scheduler.ScheduledTask
import com.velocitypowered.api.scheduler.Scheduler
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
    private val plugin: Any,
    private val api: LoginApi,
    private val onAuthenticated: (Player) -> Unit,
    private val timeoutSeconds: Long,
) : LimboSessionHandler {

    private val mm = MiniMessage.miniMessage()
    private val done = AtomicBoolean(false)
    private var attempts = 0
    private var timeoutTask: ScheduledTask? = null

    override fun onSpawn(server: Limbo, limboPlayer: LimboPlayer) {
        player.sendMessage(
            mm.deserialize("<gold><bold>misplease</bold></gold> <gray>·</gray> <white>Ingresá con <yellow>/login <contraseña></yellow></white>"),
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
            prompt("Usá <yellow>/login <contraseña></yellow> para entrar.")
            return
        }
        if (parts.size < 2) {
            prompt("Falta la contraseña: <yellow>/login <contraseña></yellow>")
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
        when (val result = runBlocking { api.login(player.username, password) }) {
            LoginResult.SUCCESS -> {
                if (!done.compareAndSet(false, true)) return
                timeoutTask?.cancel()
                player.sendMessage(
                    mm.deserialize("<green>Sesión iniciada.</green> <gray>Conectando…</gray>"),
                )
                onAuthenticated(player)
            }

            LoginResult.INVALID_CREDENTIALS ->
                prompt("Contraseña incorrecta. Intentos restantes: <yellow>${MAX_ATTEMPTS - attempts}</yellow>")

            LoginResult.UNREACHABLE ->
                prompt("El servicio de login no responde. Probá de nuevo en unos segundos.")
        }
    }

    private fun timedOut() {
        if (!done.compareAndSet(false, true)) return
        disconnect("Tiempo agotado. Reconectá e ingresá con <yellow>/login</yellow>.")
    }

    private fun prompt(text: String) {
        player.sendMessage(mm.deserialize("<gray>$text</gray>"))
    }

    private fun disconnect(text: String) {
        player.disconnect(mm.deserialize("<red>$text</red>"))
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
