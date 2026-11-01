package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SoundFeedback(
    val sound: String,
    val volume: Float = 1f,
    val pitch: Float = 1f,
)
