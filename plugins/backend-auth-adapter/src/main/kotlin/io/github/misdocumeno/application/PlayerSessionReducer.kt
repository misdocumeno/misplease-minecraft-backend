package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthState

object PlayerSessionReducer {

    fun reduce(state: PlayerSessionState, event: SessionEvent): PlayerSessionState =
        when (event) {
            SessionEvent.PreLoginStarted ->
                state.copy(authState = AuthState.CHECKING)

            is SessionEvent.AuthResult ->
                state.copy(
                    authState = if (event.authenticated)
                        AuthState.AUTHENTICATED
                    else
                        AuthState.UNAUTHENTICATED
                )

            is SessionEvent.PlayerJoined ->
                state.copy(player = event.player)

            SessionEvent.PlayerLeft ->
                state.copy(player = null)

            SessionEvent.LoginSucceeded ->
                state.copy(authState = AuthState.AUTHENTICATED)
        }
}