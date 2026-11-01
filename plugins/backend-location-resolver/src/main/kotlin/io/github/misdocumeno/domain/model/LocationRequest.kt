package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class LocationRequest(
    val requestId: Uuid,
    val username: String,
)
