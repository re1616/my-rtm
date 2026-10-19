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
    val fields: JsonObject? = null,
    /** Version the edit was based on (notes: a newer server version is reported as a conflict). */
    val baseVersion: Long? = null,
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

// ── Snapshot and entities (mirror server: SnapshotResponse, TaskResponse) ──

@Serializable
data class TaskDto(
    val id: String,
    val name: String,
    val listId: String,
    val priority: Int? = null,
    val dueAt: String? = null,
    val dueHasTime: Boolean = false,
    val startAt: String? = null,
    val estimateMin: Int? = null,
    val rrule: String? = null,
    val repeatMode: String? = null,
    val completedAt: String? = null,
    val url: String? = null,
    val parentId: String? = null,
    val locationId: String? = null,
    val postponedCount: Int = 0,
    /** Tag ids in the snapshot. */
    val tags: List<String>? = null,
    val version: Long = 1,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ListDto(
    val id: String,
    val name: String,
    val isSmart: Boolean = false,
    val query: String? = null,
    val color: String? = null,
    val sortOrder: Int = 0,
    val version: Long = 1,
    val createdAt: String,
)

@Serializable
data class TagDto(val id: String, val name: String, val color: String? = null, val version: Long = 1)

@Serializable
data class LocationDto(val id: String, val name: String)

@Serializable
data class NoteDto(val id: String, val taskId: String, val content: String, val version: Long = 1, val createdAt: String, val updatedAt: String)

@Serializable
data class AttachmentDto(val id: String, val taskId: String, val name: String, val url: String, val version: Long = 1, val createdAt: String)

@Serializable
data class SnapshotDto(
    val seq: Long? = null,
    val tasks: List<TaskDto> = emptyList(),
    val lists: List<ListDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val locations: List<LocationDto> = emptyList(),
    val notes: List<NoteDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val nextCursor: String? = null,
)

/** Mirrors the server's AccountSettings (reminder defaults are used by on-device alarms). */
@Serializable
data class AccountSettingsDto(
    val timeZone: String = "UTC",
    val locale: String = "ru",
    val theme: String = "system",
    val reminderOffsets: List<Int> = listOf(15),
    val reminderDayTime: String = "08:00",
    val pushReminders: Boolean = true,
    val emailReminders: Boolean = false,
    val digestEnabled: Boolean = false,
    val digestTime: String = "07:00",
    val weekStart: Int = 1,
    val onboarded: Boolean = false,
)

@Serializable
private data class DeviceDto(val token: String, val platform: String = "android")

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

    /** First-sync snapshot page (AND-S6). */
    open suspend fun snapshot(cursor: String?, limit: Int, accessToken: String): SnapshotDto =
        httpClient.get("/v1/sync/snapshot") {
            bearerAuth(accessToken)
            if (cursor != null) parameter("cursor", cursor)
            parameter("limit", limit.toString())
        }.body()

    /** Server version of one task (after a conflict, AND-S5). Throws on 404. */
    open suspend fun getTask(id: String, accessToken: String): TaskDto =
        httpClient.get("/v1/tasks/$id") { bearerAuth(accessToken) }.body()

    open suspend fun taskNotes(taskId: String, accessToken: String): List<NoteDto> =
        httpClient.get("/v1/tasks/$taskId/notes") { bearerAuth(accessToken) }.body()

    /** FCM token of this device for background wake-ups (AND-S4). */
    open suspend fun registerDevice(fcmToken: String, accessToken: String) {
        httpClient.post("/v1/push/devices") {
            bearerAuth(accessToken)
            setBody(DeviceDto(fcmToken))
        }
    }

    open suspend fun accountSettings(accessToken: String): AccountSettingsDto =
        httpClient.get("/v1/account/settings") { bearerAuth(accessToken) }.body()

    companion object {
        /** SYNC-7: protocol version sent with every request. */
        const val SYNC_PROTOCOL_VERSION = "2"

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
                    header("Taskly-Sync-Version", SYNC_PROTOCOL_VERSION)
                }
            }
            return TasklyApiClient(httpClient)
        }
    }
}