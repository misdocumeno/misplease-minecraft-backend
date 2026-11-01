package io.github.misdocumeno.domain

import kotlinx.serialization.Serializable

@Serializable
data class ChatBroadcast(
    val messageId: String,
    val audience: ChatAudience,
    val rendered: RenderedChatMessage,
)
