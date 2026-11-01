package io.github.misdocumeno.infra.http

import io.github.misdocumeno.domain.AuthAction
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class KtorAuthAction(private val client: HttpClient) : AuthAction {

    override suspend fun authenticate(username: String, password: String): Boolean {
        val result = client.post("http://auth:3000/session/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body<LoginResult>()
        return result is LoginResult.Success
    }
}