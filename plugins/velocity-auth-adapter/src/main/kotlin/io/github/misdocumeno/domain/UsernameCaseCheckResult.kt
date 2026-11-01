package io.github.misdocumeno.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

//  TODO: move to infra, as a DTO, and translate to domain
@Serializable
sealed interface UsernameCaseCheckResult {

    @Serializable
    @SerialName("NotFound")
    object NotFound : UsernameCaseCheckResult

    @Serializable
    @SerialName("ExactMatch")
    object ExactMatch : UsernameCaseCheckResult

    @Serializable
    @SerialName("WrongCase")
    data class WrongCase(val correctUsername: String) : UsernameCaseCheckResult
}


