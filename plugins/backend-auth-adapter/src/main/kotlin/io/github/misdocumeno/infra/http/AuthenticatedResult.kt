package io.github.misdocumeno.infra.http

import kotlinx.serialization.Serializable

@Serializable
data class AuthenticatedResult(val authenticated: Boolean)