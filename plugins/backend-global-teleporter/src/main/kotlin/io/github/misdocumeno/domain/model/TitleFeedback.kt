package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TitleFeedback(
    val title: String,
    val subtitle: String? = null,
    val fadeIn: Long = 100L,
    val stay: Long = 700L,
    val fadeOut: Long = 200L,
)
