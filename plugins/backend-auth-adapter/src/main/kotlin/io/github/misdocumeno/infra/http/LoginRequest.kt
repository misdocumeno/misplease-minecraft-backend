package io.github.misdocumeno.infra.http

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)
