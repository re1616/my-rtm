package com.taskly.android.core.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ── Auth DTOs (mirror server: AuthResponse) ──

@Serializable
data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val userId: String
)

@Serializable
private data class RegisterRequest(
    val email: String,
    val password: String
)

@Serializable
private data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
private data class RefreshTokenRequest(
    val refreshToken: String
)

// ── Sync push DTOs (mirror server: SyncRequest / SyncResponse) ──

@Serializable
data class SyncOpDto(
    val opId: String,
    val scope: String,
    val entity: String,
    val entityId: String,
    val type: String,
    val fields: JsonObject? = null
)

@Serializable
private data class SyncRequestDto(
    val ops: List<SyncOpDto>
)

@Serializable
data class SyncResultDto(
    val opId: String,
    val status: String,
    val entityId: String? = null,
    val version: Long? = null,
    val error: String? = null
)

@Serializable
data class SyncResponseDto(
    val results: List<SyncResultDto>,
    val cursor: String? = null
)

// ── Sync delta DTOs (mirror server: SyncChangesResponse / change-log) ──

@Serializable
data class ChangeEntryDto(
    val seq: Long,
    val scopeId: String,
    val entity: String,
    val entityId: String,
    val op: String,
    val payload: JsonElement? = null,
    val actorId: String,
    val ts: String
)

@Serializable
data class SyncChangesResponseDto(
    val changes: List<ChangeEntryDto>,
    val cursor: String? = null
)

// ── Client ──

/** Socket-like HTTP client wrapper. Made open so tests can inject a fake. */
open class TasklyApiClient(private val httpClient: HttpClient) {

    open suspend fun register(email: String, password: String): AuthResult {
        val response = httpClient.post("/v1/auth/register") {
            setBody(RegisterRequest(email, password))
        }
        return response.body()
    }

    open suspend fun login(email: String, password: String): AuthResult {
        val response = httpClient.post("/v1/auth/login") {
            setBody(LoginRequest(email, password))
        }
        return response.body()
    }

    open suspend fun refreshToken(token: String): AuthResult {
        val response = httpClient.post("/v1/auth/refresh") {
            setBody(RefreshTokenRequest(token))
        }
        return response.body()
    }

    /** Push sync operations to the server. Returns per-op results. */
    open suspend fun syncPush(ops: List<SyncOpDto>, accessToken: String): List<SyncResultDto> {
        val response = httpClient.post("/v1/sync") {
            bearerAuth(accessToken)
            setBody(SyncRequestDto(ops))
        }
        val wrapped: SyncResponseDto = response.body()
        return wrapped.results
    }

    /** Pull changes since [afterSeq] in [scope]. */
    open suspend fun syncDelta(
        scope: String,
        afterSeq: Long? = null,
        limit: Int = 100,
        accessToken: String
    ): SyncChangesResponseDto {
        val response = httpClient.get("/v1/sync/changes") {
            bearerAuth(accessToken)
            parameter("scope", scope)
            if (afterSeq != null) parameter("after", afterSeq.toString())
            parameter("limit", limit.toString())
        }
        return response.body()
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        fun create(baseUrl: String): TasklyApiClient {
            val httpClient = HttpClient(CIO) {
                // 4xx/5xx become exceptions instead of being decoded as data
                expectSuccess = true
                install(ContentNegotiation) {
                    json(json)
                }
                install(WebSockets)
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                }
                defaultRequest {
                    url(baseUrl)
                    contentType(ContentType.Application.Json)
                }
            }
            return TasklyApiClient(httpClient)
        }
    }
}