package io.github.misdocumeno.infra.http

import io.github.misdocumeno.domain.AuthApi
import io.github.misdocumeno.domain.Username
import io.github.misdocumeno.domain.UsernameCaseCheckResult
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType

class KtorAuthApi(private val client: HttpClient) : AuthApi {

    override suspend fun checkUsernameCase(username: String): UsernameCaseCheckResult {
        return client.get("http://auth:3000/casing/check") {
            parameter("username", username)
        }.body()
    }

    override suspend fun invalidateSession(username: String) {
        client.post("http://auth:3000/session/invalidate") {
            contentType(ContentType.Application.Json)
            setBody(Username(username))
        }
    }
}