package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class LocationResponse(
    val requestId: Uuid,
    val location: GlobalLocation,
)