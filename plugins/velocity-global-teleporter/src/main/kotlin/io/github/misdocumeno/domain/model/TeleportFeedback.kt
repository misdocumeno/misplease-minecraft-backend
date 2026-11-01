package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TeleportFeedback(
    val chat: String? = null,
    val title: TitleFeedback? = null,
    val sound: SoundFeedback? = null,
)
