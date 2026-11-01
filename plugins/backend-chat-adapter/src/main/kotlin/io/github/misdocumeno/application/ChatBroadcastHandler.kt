package io.github.misdocumeno.application

import io.github.misdocumeno.domain.ChatBroadcast

interface ChatBroadcastHandler {
    fun handle(broadcast: ChatBroadcast)
}