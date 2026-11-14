package io.github.misdocumeno.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface UsernameCaseCheckResult {

    @Serializable
    @SerialName("NotFound")
    object NotFound : UsernameCaseCheckResult

    @Serializable
    @SerialName("ExactMatch")
    data class ExactMatch(val username: String, val uuid: String) : UsernameCaseCheckResult

    @Serializable
    @SerialName("WrongCase")
    data class WrongCase(val correctUsername: String, val uuid: String) : UsernameCaseCheckResult
}
