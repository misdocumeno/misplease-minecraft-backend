package io.github.misdocumeno.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed interface ChatAudience {

    @Serializable
    @SerialName("Global")
    object Global : ChatAudience

    @Serializable
    @SerialName("SpecificPlayers")
    data class SpecificPlayers(val uuids: Set<Uuid>) : ChatAudience
}