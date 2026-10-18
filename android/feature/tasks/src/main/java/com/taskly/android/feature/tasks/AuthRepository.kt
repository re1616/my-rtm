package com.taskly.android.feature.tasks

import android.content.Context
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.AuthResult
import com.taskly.android.core.network.TasklyApiClient

/**
 * Auth repository with persistent token storage via SharedPreferences.
 * Tokens survive app restarts.
 *
 * Hilt readiness: add @Inject constructor annotation when Hilt codegen
 * is active — Context, ApiClient, and Database are all Hilt-provided.
 */
class AuthRepository(
    private val apiClient: TasklyApiClient,
    private val db: TasklyDatabase,
    private val context: Context
) {
    private var currentToken: String? = null
    private var currentRefreshToken: String? = null
    private var currentUserId: String? = null

    companion object {
        private const val PREFS_NAME = "taskly_auth"
        private const val KEY_TOKEN = "auth_access_token"
        private const val KEY_REFRESH = "auth_refresh_token"
        private const val KEY_USER_ID = "auth_user_id"
    }

    init {
        restoreTokens()
    }

    private fun restoreTokens() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentToken = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }
        currentRefreshToken = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotEmpty() }
        currentUserId = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotEmpty() }
    }

    private fun persistTokens() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (currentToken != null) {
            prefs.putString(KEY_TOKEN, currentToken)
            prefs.putString(KEY_REFRESH, currentRefreshToken ?: "")
            prefs.putString(KEY_USER_ID, currentUserId ?: "")
        } else {
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_REFRESH)
            prefs.remove(KEY_USER_ID)
        }
        prefs.apply()
    }

    fun isLoggedIn(): Boolean = currentToken != null

    fun getAccessToken(): String? = currentToken

    fun getUserId(): String? = currentUserId

    suspend fun register(email: String, password: String): Result<AuthResult> {
        return try {
            val result = apiClient.register(email, password)
            currentToken = result.accessToken
            currentRefreshToken = result.refreshToken
            currentUserId = result.userId
            persistTokens()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(email: String, password: String): Result<AuthResult> {
        return try {
            val result = apiClient.login(email, password)
            currentToken = result.accessToken
            currentRefreshToken = result.refreshToken
            currentUserId = result.userId
            persistTokens()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshToken(): Result<AuthResult> {
        val refresh = currentRefreshToken ?: return Result.failure(IllegalStateException("No refresh token available"))
        return try {
            val result = apiClient.refreshToken(refresh)
            currentToken = result.accessToken
            currentRefreshToken = result.refreshToken
            currentUserId = result.userId
            persistTokens()
            Result.success(result)
        } catch (e: Exception) {
            currentToken = null
            currentRefreshToken = null
            currentUserId = null
            persistTokens()
            Result.failure(e)
        }
    }
}