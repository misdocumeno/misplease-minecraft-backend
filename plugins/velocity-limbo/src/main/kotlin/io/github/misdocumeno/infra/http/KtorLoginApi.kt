package io.github.misdocumeno.infra.http

import io.github.misdocumeno.domain.LoginApi
import io.github.misdocumeno.domain.LoginResult
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger

/**
 * `POST {baseUrl}/session/login`.
 *
 * The path lives here, not in `AUTH_URL`: the auth service mounts the game
 * session routes under `/session` (`services/auth/src/index.ts`). Posting to
 * `/login` returns 404, which used to surface as "el servicio de login no
 * responde".
 */
class KtorLoginApi(
    private val client: HttpClient,
    private val baseUrl: String,
    private val logger: Logger,
) : LoginApi {

    override suspend fun login(username: String, password: String): LoginResult {
        return try {
            // Encode here and send a plain string body instead of installing Ktor's
            // ContentNegotiation: its kotlinx provider service file ships with a
            // leading space, which Shadow's relocation does not rewrite, so the
            // provider is not found after shading.
            val payload = Json.encodeToString(Credentials(username, password))
            val response = client.post("$baseUrl/session/login") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            when (response.status) {
                HttpStatusCode.OK -> LoginResult.SUCCESS
                HttpStatusCode.Unauthorized -> LoginResult.INVALID_CREDENTIALS
                else -> {
                    logger.warn(
                        "login for {} failed: HTTP {} {}",
                        username,
                        response.status.value,
                        response.bodyAsText().take(200),
                    )
                    LoginResult.UNREACHABLE
                }
            }
        } catch (e: Exception) {
            logger.warn("login for {} failed: {}: {}", username, e.javaClass.simpleName, e.message)
            LoginResult.UNREACHABLE
        }
    }

    @Serializable
    private data class Credentials(val username: String, val password: String)
}
