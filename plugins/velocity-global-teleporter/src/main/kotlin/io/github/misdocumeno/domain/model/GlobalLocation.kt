package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GlobalLocation(
    val server: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)
