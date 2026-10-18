package com.taskly.android.feature.tasks

import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.AuthResult
import com.taskly.android.core.network.TasklyApiClient

class AuthRepository(
    private val apiClient: TasklyApiClient,
    private val db: TasklyDatabase
) {
    private var currentToken: String? = null
    private var currentRefreshToken: String? = null
    private var currentUserId: String? = null

    fun isLoggedIn(): Boolean = currentToken != null

    fun getAccessToken(): String? = currentToken

    fun getUserId(): String? = currentUserId

    suspend fun register(email: String, password: String, name: String): Result<AuthResult> {
        return try {
            val result = apiClient.register(email, password, name)
            currentToken = result.accessToken
            currentRefreshToken = result.refreshToken
            currentUserId = result.userId
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
            Result.success(result)
        } catch (e: Exception) {
            // Clear tokens on refresh failure — forces re-login
            currentToken = null
            currentRefreshToken = null
            currentUserId = null
            Result.failure(e)
        }
    }
}