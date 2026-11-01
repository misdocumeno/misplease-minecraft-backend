package io.github.misdocumeno.application

interface AuthTimeout {
    fun startTimeout(player: PlayerHandle)
    fun cancelTimeout(player: PlayerHandle)
}