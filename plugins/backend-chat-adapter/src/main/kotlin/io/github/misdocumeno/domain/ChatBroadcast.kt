package io.github.misdocumeno.domain

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ChatBroadcast(
    val messageId: Uuid,
    val senderUUID: Uuid,
    val audience: ChatAudience,
    val rendered: RenderedChatMessage,
)
