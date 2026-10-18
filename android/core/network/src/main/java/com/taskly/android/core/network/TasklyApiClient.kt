package com.taskly.android.core.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresIn: Long
)

@Serializable
data class OpResult(
    val opId: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class DeltaResult(
    val entries: List<DeltaEntry>,
    val nextSeq: Long,
    val hasMore: Boolean
)

@Serializable
data class DeltaEntry(
    val entityType: String,
    val entityId: String,
    val operation: String,
    val data: JsonObject
)

class TasklyApiClient(private val httpClient: HttpClient) {

    suspend fun register(email: String, password: String, name: String): AuthResult {
        val response = httpClient.post("/v1/auth/register") {
            setBody(mapOf("email" to email, "password" to password, "name" to name))
        }
        return response.body()
    }

    suspend fun login(email: String, password: String): AuthResult {
        val response = httpClient.post("/v1/auth/login") {
            setBody(mapOf("email" to email, "password" to password))
        }
        return response.body()
    }

    suspend fun refreshToken(refreshToken: String): AuthResult {
        val response = httpClient.post("/v1/auth/refresh") {
            setBody(mapOf("refreshToken" to refreshToken))
        }
        return response.body()
    }

    suspend fun syncPush(ops: List<JsonObject>, accessToken: String): List<OpResult> {
        val response = httpClient.post("/v1/sync/push") {
            bearerAuth(accessToken)
            setBody(ops)
        }
        return response.body()
    }

    suspend fun syncDelta(scope: String, afterSeq: Long, accessToken: String): DeltaResult {
        val response = httpClient.get("/v1/sync/delta") {
            bearerAuth(accessToken)
            parameter("scope", scope)
            parameter("afterSeq", afterSeq.toString())
        }
        return response.body()
    }

    companion object {
        private const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        fun create(baseUrl: String = DEFAULT_BASE_URL): TasklyApiClient {
            val httpClient = HttpClient {
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