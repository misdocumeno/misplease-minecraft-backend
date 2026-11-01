package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.TeleportRequest

interface TeleportHandler {
    fun handle(event: TeleportRequest)
}