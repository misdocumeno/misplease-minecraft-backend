package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TeleportRequest(
    val username: String,
    val location: GlobalLocation,
    val feedback: TeleportFeedback? = null,
)