package io.github.misdocumeno.infra.velocity

import io.github.misdocumeno.application.AuthEventHandler
import io.github.misdocumeno.domain.model.AuthEvent

class VelocityAuthEventHandler(
    private val scheduleRebuilds: () -> Unit,
) : AuthEventHandler {

    override fun handle(event: AuthEvent) {
        // /login may fire before ServerPostConnect has settled currentServer.
        scheduleRebuilds()
    }
}
