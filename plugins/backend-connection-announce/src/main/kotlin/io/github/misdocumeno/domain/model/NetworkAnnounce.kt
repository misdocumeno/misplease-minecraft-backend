package io.github.misdocumeno.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface NetworkAnnounce {
    @Serializable
    @SerialName("Death")
    data class Death(
        val username: String,
        val miniMessage: String,
    ) : NetworkAnnounce
}
