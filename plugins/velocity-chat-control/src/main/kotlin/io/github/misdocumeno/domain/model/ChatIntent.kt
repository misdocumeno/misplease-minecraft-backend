package io.github.misdocumeno.domain.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ChatIntent(
    val messageId: Uuid,
    val senderUUID: Uuid,
    val rawMessage: String,
    val timestamp: Long,
)
