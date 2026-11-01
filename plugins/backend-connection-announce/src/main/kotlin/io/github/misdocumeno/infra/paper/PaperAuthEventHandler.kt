package io.github.misdocumeno.infra.paper

import io.github.misdocumeno.application.AuthEventHandler
import io.github.misdocumeno.domain.AuthenticatedRepository
import io.github.misdocumeno.domain.model.AuthEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Server
import org.bukkit.entity.Player

class PaperAuthEventHandler(
    private val server: Server,
    private val authRepo: AuthenticatedRepository,
) : AuthEventHandler {

    override fun handle(event: AuthEvent) {
        val authedAudience = server.filterAudience { p ->
            (p as? Player)?.name?.let { authRepo.isAuthenticated(it) } ?: false
        }

        val key = when (event) {
            is AuthEvent.Authenticated -> "multiplayer.player.joined"
            is AuthEvent.Invalidated -> "multiplayer.player.left"
        }

        val component = Component.translatable(key, NamedTextColor.YELLOW, Component.text(event.username))

        authedAudience.sendMessage(component)
    }
}