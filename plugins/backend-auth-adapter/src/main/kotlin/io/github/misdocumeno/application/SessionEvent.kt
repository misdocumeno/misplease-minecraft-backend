package io.github.misdocumeno.application

sealed interface SessionEvent {
    data object PreLoginStarted : SessionEvent
    data class AuthResult(val authenticated: Boolean) : SessionEvent
    data class PlayerJoined(val player: PlayerHandle) : SessionEvent
    data object PlayerLeft : SessionEvent
    data object LoginSucceeded : SessionEvent
}