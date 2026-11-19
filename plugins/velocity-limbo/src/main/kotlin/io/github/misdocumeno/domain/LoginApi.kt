package io.github.misdocumeno.domain

/** Result of a proxy-side login attempt, mapped from the auth service. */
enum class LoginResult {
    SUCCESS,
    INVALID_CREDENTIALS,
    UNREACHABLE,
}

interface LoginApi {
    /**
     * Asks the auth service to verify the password. The service owns the session:
     * a SUCCESS means the Redis session used by the backends already exists.
     *
     * [loginId] is the correlation id logged here and by the auth service (sent as
     * `X-Request-Id`), so one login can be followed from the limbo to Redis.
     */
    suspend fun login(username: String, password: String, loginId: String): LoginResult
}
