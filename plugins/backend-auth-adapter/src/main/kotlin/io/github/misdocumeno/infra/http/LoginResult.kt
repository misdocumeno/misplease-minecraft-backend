package io.github.misdocumeno.infra.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface LoginResult {

    @Serializable
    @SerialName("Success")
    object Success : LoginResult

    @Serializable
    @SerialName("InvalidCredentials")
    object InvalidCredentials : LoginResult
}