package io.github.misdocumeno.application

import io.github.misdocumeno.domain.ChatIntent

interface ChatIntentPublisher {
    fun publish(intent: ChatIntent)
}