package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TeleportAskNotification(
    val from: String,
    val to: String,
    val type: TeleportAskType,
)
