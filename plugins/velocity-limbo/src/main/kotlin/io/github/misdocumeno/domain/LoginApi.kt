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
     */
    suspend fun login(username: String, password: String): LoginResult
}
