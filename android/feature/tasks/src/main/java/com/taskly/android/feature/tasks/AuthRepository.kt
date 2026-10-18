package com.taskly.android.feature.tasks

import com.taskly.android.core.network.AuthResult
import com.taskly.android.core.network.TasklyApiClient
import com.taskly.android.core.sync.SyncSession

/** Sign-in against the server; tokens live in [SyncSession] so background sync can use them. */
class AuthRepository(
    private val api: TasklyApiClient,
    private val session: SyncSession,
) {
    fun isLoggedIn(): Boolean = session.accessToken != null && session.userId != null

    fun getAccessToken(): String? = session.accessToken

    fun getUserId(): String? = session.userId

    suspend fun register(email: String, password: String): Result<AuthResult> =
        runCatching { api.register(email, password) }.onSuccess(::store)

    suspend fun login(email: String, password: String): Result<AuthResult> =
        runCatching { api.login(email, password) }.onSuccess(::store)

    suspend fun refreshToken(): Result<AuthResult> {
        val refresh = session.refreshToken ?: return Result.failure(IllegalStateException("No refresh token"))
        return runCatching { api.refreshToken(refresh) }
            .onSuccess(::store)
            .onFailure { session.signOut() }
    }

    fun logout() = session.signOut()

    private fun store(result: AuthResult) = session.signIn(result.accessToken, result.refreshToken, result.userId)
}
