package io.github.misdocumeno.domain

import io.github.misdocumeno.domain.model.ChatBroadcast

interface ChatBroadcaster {
    fun broadcast(broadcast: ChatBroadcast)
}