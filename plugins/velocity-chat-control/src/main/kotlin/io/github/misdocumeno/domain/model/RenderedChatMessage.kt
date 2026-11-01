package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RenderedChatMessage(val miniMessage: String)