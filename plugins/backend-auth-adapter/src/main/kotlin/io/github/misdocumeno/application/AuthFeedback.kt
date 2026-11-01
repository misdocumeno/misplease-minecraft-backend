package io.github.misdocumeno.application

interface AuthFeedback {
    fun onBecameUnauthenticated(player: PlayerHandle)
    fun onAuthenticated(player: PlayerHandle)
    fun onWrongPassword(player: PlayerHandle)
    fun onAlreadyLoggedIn(player: PlayerHandle)
}