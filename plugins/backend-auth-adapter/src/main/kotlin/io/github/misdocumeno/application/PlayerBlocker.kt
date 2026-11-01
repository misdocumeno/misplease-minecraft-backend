package io.github.misdocumeno.application

interface PlayerBlocker {
    fun block(player: PlayerHandle)
    fun unblock(player: PlayerHandle)
}