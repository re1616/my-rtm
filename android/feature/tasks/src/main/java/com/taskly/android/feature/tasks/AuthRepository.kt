package com.taskly.android.feature.tasks

import com.taskly.android.core.network.AccountSettingsDto
import com.taskly.android.core.network.AuthResult
import com.taskly.android.core.network.TasklyApiClient
import com.taskly.android.core.sync.SessionTokens
import com.taskly.android.core.sync.SyncSession
import kotlinx.serialization.json.JsonObject

/** Sign-in against the server; tokens live in [SyncSession] so background sync can use them. */
open class AuthRepository(
    private val api: TasklyApiClient,
    private val session: SyncSession,
) {
    private val tokens by lazy { SessionTokens(session, api) }

    open fun isLoggedIn(): Boolean = session.accessToken != null && session.userId != null

    open fun getAccessToken(): String? = session.accessToken

    open fun getUserId(): String? = session.userId

    open suspend fun register(email: String, password: String): Result<AuthResult> =
        runCatching { api.register(email, password) }.onSuccess(::store)

    /** Two-factor accounts first get `totp_required`; the UI then asks for the code (AUTH-2FA). */
    open suspend fun login(email: String, password: String, totpCode: String? = null): Result<AuthResult> =
        runCatching { api.login(email, password, totpCode?.takeIf { it.isNotBlank() }) }.onSuccess(::store)

    open suspend fun forgotPassword(email: String): Result<Unit> = runCatching { api.forgotPassword(email) }

    open fun isTotpRequired(e: Throwable): Boolean = e.message?.contains("totp_required") == true

    open suspend fun refreshToken(): Result<AuthResult> {
        val refresh = session.refreshToken ?: return Result.failure(IllegalStateException("No refresh token"))
        return runCatching { api.refreshToken(refresh) }
            .onSuccess(::store)
            .onFailure { session.signOut() }
    }

    /** Runs an authorised call, refreshing the access token once on 401. */
    private suspend fun <T> authed(block: suspend (String) -> T): Result<T> = runCatching {
        val token = tokens.accessToken() ?: error("Not signed in")
        try {
            block(token)
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status.value != 401) throw e
            block(tokens.refresh() ?: throw e)
        }
    }

    open suspend fun settings(): Result<AccountSettingsDto> = authed { api.accountSettings(it) }

    open suspend fun updateSettings(patch: JsonObject): Result<AccountSettingsDto> = authed { api.patchAccountSettings(patch, it) }

    open fun logout() = session.signOut()

    private fun store(result: AuthResult) = session.signIn(result.accessToken, result.refreshToken, result.userId)
}
