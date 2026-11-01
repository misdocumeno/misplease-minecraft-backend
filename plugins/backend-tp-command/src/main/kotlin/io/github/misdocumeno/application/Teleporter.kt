package io.github.misdocumeno.application

import io.github.misdocumeno.domain.model.GlobalLocation
import io.github.misdocumeno.domain.model.TeleportFeedback

interface Teleporter {
    fun teleport(username: String, location: GlobalLocation, feedback: TeleportFeedback? = null)
}