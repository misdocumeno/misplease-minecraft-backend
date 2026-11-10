package io.github.misdocumeno.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AuthEvent {
    val username: String

    @Serializable
    @SerialName("Authenticated")
    data class Authenticated(override val username: String) : AuthEvent

    @Serializable
    @SerialName("Invalidated")
    data class Invalidated(override val username: String) : AuthEvent
}
