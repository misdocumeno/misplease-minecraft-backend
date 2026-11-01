package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthState

class PlayerSession(
    private val blocker: PlayerBlocker,
    private val feedback: AuthFeedback,
    private val timeout: AuthTimeout,
) {

    private var state = PlayerSessionState(AuthState.CHECKING, null)

    fun snapshot() = state

    @Synchronized
    fun dispatch(event: SessionEvent) {
        val old = state
        val new = PlayerSessionReducer.reduce(old, event)
        state = new
        handleSideEffects(old, new)
    }

    private fun handleSideEffects(old: PlayerSessionState, new: PlayerSessionState) {
        val player = new.player ?: return
        if (new.authState != AuthState.AUTHENTICATED) {
            blocker.block(player)
            // first time joining, prompted to use /login
            if (new.authState == AuthState.UNAUTHENTICATED) {
                feedback.onBecameUnauthenticated(player)
                timeout.startTimeout(player)
            }
        } else {
            blocker.unblock(player)
            // only show feedback after an actual login
            if (old.authState == AuthState.UNAUTHENTICATED) {
                feedback.onAuthenticated(player)
                timeout.cancelTimeout(player)
            }
        }
    }
}