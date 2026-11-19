package io.github.misdocumeno.infra.http

import io.github.misdocumeno.domain.AuthApi
import io.github.misdocumeno.domain.Username
import io.github.misdocumeno.domain.UsernameCaseCheckResult
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

class KtorAuthApi(private val client: HttpClient) : AuthApi {

    override suspend fun checkUsernameCase(username: String): UsernameCaseCheckResult {
        return client.get("http://auth:3000/casing/check") {
            parameter("username", username)
        }.body()
    }

    override suspend fun invalidateSession(username: String, source: String, requestId: String) {
        client.post("http://auth:3000/session/invalidate") {
            contentType(ContentType.Application.Json)
            header(REQUEST_ID_HEADER, requestId)
            setBody(InvalidateRequest(username, source))
        }
    }

    override suspend fun clearAllSessions(source: String, requestId: String) {
        client.post("http://auth:3000/session/clear-all") {
            contentType(ContentType.Application.Json)
            header(REQUEST_ID_HEADER, requestId)
            setBody(ClearAllRequest(source))
        }
    }

    override suspend fun listAuthenticated(): List<String> {
        return client.get("http://auth:3000/session/authenticated").body<AuthenticatedList>().usernames
    }

    @Serializable
    private data class AuthenticatedList(val usernames: List<String>)

    @Serializable
    private data class InvalidateRequest(val username: String, val source: String)

    @Serializable
    private data class ClearAllRequest(val source: String)

    private companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
    }
}
