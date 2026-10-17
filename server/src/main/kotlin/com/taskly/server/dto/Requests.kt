package com.taskly.server.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ============================================================
// Auth
// ============================================================

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val tz: String? = null,
    val locale: String? = null
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: String
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

// ============================================================
// Tasks
// ============================================================

@Serializable
data class CreateTaskRequest(
    val name: String,
    val listId: String? = null,
    val priority: Int? = null,
    val dueAt: String? = null,
    val dueHasTime: Boolean? = null,
    val startAt: String? = null,
    val estimateMin: Int? = null,
    val rrule: String? = null,
    val repeatMode: String? = null,
    val url: String? = null,
    val smartAdd: Boolean? = null,
    val tags: List<String>? = null
)

@Serializable
data class UpdateTaskRequest(
    val name: String? = null,
    val listId: String? = null,
    val priority: Int? = null,
    val dueAt: String? = null,
    val dueHasTime: Boolean? = null,
    val startAt: String? = null,
    val estimateMin: Int? = null,
    val rrule: String? = null,
    val repeatMode: String? = null,
    val url: String? = null,
    val completedAt: String? = null,
    val version: Long
)

@Serializable
data class TaskResponse(
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
    val tags: List<String>? = null,
    val version: Long,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CompleteTaskResponse(
    val task: TaskResponse,
    val nextInstance: TaskResponse? = null
)

// ============================================================
// Lists
// ============================================================

@Serializable
data class ListResponse(
    val id: String,
    val name: String,
    val isSmart: Boolean = false,
    val query: String? = null,
    val color: String? = null,
    val sortOrder: Int = 0,
    val version: Long,
    val createdAt: String
)

@Serializable
data class CreateListRequest(
    val name: String,
    val color: String? = null,
    val isSmart: Boolean? = null,
    val query: String? = null
)

@Serializable
data class UpdateListRequest(
    val name: String? = null,
    val color: String? = null,
    val isSmart: Boolean? = null,
    val query: String? = null,
    val sortOrder: Int? = null,
    val archived: Boolean? = null,
    val version: Long
)

// ============================================================
// Tags
// ============================================================

@Serializable
data class TagResponse(
    val id: String,
    val name: String,
    val color: String? = null,
    val version: Long
)

@Serializable
data class CreateTagRequest(
    val name: String,
    val color: String? = null
)

// ============================================================
// Sync
// ============================================================

@Serializable
data class SyncRequest(
    val ops: List<SyncOperation>
)

@Serializable
data class SyncOperation(
    val opId: String,
    val scope: String,
    val entity: String,
    val entityId: String,
    val type: String,
    val fields: Map<String, JsonElement?>? = null,
    val baseVersion: Long? = null,
    val clientTs: String? = null,
    val deviceId: String? = null
)

@Serializable
data class SyncResponse(
    val results: List<SyncResult>,
    val cursor: String? = null
)

@Serializable
data class SyncResult(
    val opId: String,
    val status: String,
    val entityId: String? = null,
    val version: Long? = null,
    val error: String? = null
)

@Serializable
data class ChangeLogEntry(
    val seq: Long,
    val scopeId: String,
    val entity: String,
    val entityId: String,
    val op: String,
    val payload: Map<String, JsonElement?>? = null,
    val actorId: String,
    val ts: String
)

@Serializable
data class ChangesRequest(
    val scope: String,
    val after: Long? = null,
    val limit: Int? = null
)

// ============================================================
// Common
// ============================================================

@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null
)

@Serializable
data class PaginatedResponse<T>(
    val data: List<T>,
    val cursor: String? = null
)