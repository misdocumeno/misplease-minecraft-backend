package io.github.misdocumeno.application

import io.github.misdocumeno.domain.AuthState

data class PlayerSessionState(
    val authState: AuthState,
    val player: PlayerHandle?
)
