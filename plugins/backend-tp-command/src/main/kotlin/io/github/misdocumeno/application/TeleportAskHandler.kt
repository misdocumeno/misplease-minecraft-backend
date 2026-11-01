package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.TeleportAskNotification

interface TeleportAskHandler {
    fun handle(notification: TeleportAskNotification)
}