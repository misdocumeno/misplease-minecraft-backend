package io.github.misdocumeno.infra.http

import io.github.misdocumeno.domain.LoginApi
import io.github.misdocumeno.domain.LoginResult
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KtorLoginApi(
    private val client: HttpClient,
    private val baseUrl: String,
) : LoginApi {

    override suspend fun login(username: String, password: String): LoginResult {
        return try {
            // Encode here and send a plain string body instead of installing Ktor's
            // ContentNegotiation: its kotlinx provider service file ships with a
            // leading space, which Shadow's relocation does not rewrite, so the
            // provider is not found after shading.
            val payload = Json.encodeToString(Credentials(username, password))
            val response = client.post("$baseUrl/login") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            when (response.status) {
                HttpStatusCode.OK -> LoginResult.SUCCESS
                HttpStatusCode.Unauthorized -> LoginResult.INVALID_CREDENTIALS
                else -> LoginResult.UNREACHABLE
            }
        } catch (e: Exception) {
            LoginResult.UNREACHABLE
        }
    }

    @Serializable
    private data class Credentials(val username: String, val password: String)
}
