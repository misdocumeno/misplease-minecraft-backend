package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ChatBroadcast(
    val messageId: Uuid,
    val audience: ChatAudience,
    val rendered: RenderedChatMessage,
)