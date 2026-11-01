package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.AuthEvent

interface AuthEventHandler {
    fun handle(event: AuthEvent)
}