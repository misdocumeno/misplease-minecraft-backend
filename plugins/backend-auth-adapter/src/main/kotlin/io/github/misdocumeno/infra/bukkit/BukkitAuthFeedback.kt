package io.github.misdocumeno.infra.bukkit

import io.github.misdocumeno.application.AuthFeedback
import io.github.misdocumeno.application.PlayerHandle
import net.kyori.adventure.title.Title
import java.time.Duration

class BukkitAuthFeedback(private val config: BukkitConfig) : AuthFeedback {

    override fun onBecameUnauthenticated(player: PlayerHandle) {
        val p = (player as BukkitPlayerHandle).unwrap()

        p.showTitle(
            Title.title(
                config.unauthenticatedTitle(), config.unauthenticatedSubtitle(), Title.Times.times(
                    Duration.ofMillis(250), Duration.ofMinutes(5), Duration.ofMillis(0)
                )
            )
        )
    }

    override fun onAuthenticated(player: PlayerHandle) {
        val p = (player as BukkitPlayerHandle).unwrap()

        p.showTitle(
            Title.title(
                config.authenticatedTitle(), config.authenticatedSubtitle(), Title.Times.times(
                    Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofSeconds(1)
                )
            )
        )
    }

    override fun onWrongPassword(player: PlayerHandle) {
        val p = (player as BukkitPlayerHandle).unwrap()
        p.sendMessage(config.wrongPasswordMessage())
        config.wrongPasswordSound()?.let {
            p.playSound(p.location, it, 1f, 1f)
        }
    }

    override fun onAlreadyLoggedIn(player: PlayerHandle) {
        val p = (player as BukkitPlayerHandle).unwrap()
        p.sendMessage(config.alreadyLoggedInMessage())
        config.alreadyLoggedInSound()?.let {
            p.playSound(p.location, it, 1f, 1f)
        }
    }
}