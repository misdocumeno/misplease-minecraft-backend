package io.github.misdocumeno.infra.velocity

import io.github.misdocumeno.application.AuthEventHandler
import io.github.misdocumeno.domain.model.AuthEvent

class VelocityAuthEventHandler(
    private val rebuilder: GlobalTabListRebuilder,
) : AuthEventHandler {

    override fun handle(event: AuthEvent) {
        rebuilder.rebuildGlobalTabList()
    }
}