package com.taskly.android.core.database

/**
 * Entities mirroring server data model for local storage.
 * Room annotations will be added in the coder phase (AND-S1).
 * Phase 1 scaffold: pure data classes without persistence framework.
 */

data class TaskEntity(
    val id: String,
    val ownerId: String,
    val listId: String,
    val parentId: String? = null,
    val name: String,
    val priority: Int? = null,
    val dueAt: String? = null,
    val dueHasTime: Boolean = false,
    val startAt: String? = null,
    val estimateMin: Int? = null,
    val rrule: String? = null,
    val repeatMode: String? = null,
    val completedAt: String? = null,
    val postponedCount: Int = 0,
    val url: String? = null,
    val version: Long = 1,
    val deletedAt: String? = null,
    val createdAt: String,
    val updatedAt: String
)

data class ListEntity(
    val id: String,
    val ownerId: String,
    val name: String,
    val isSmart: Boolean = false,
    val query: String? = null,
    val color: String? = null,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val version: Long = 1,
    val deletedAt: String? = null,
    val createdAt: String,
    val updatedAt: String
)

data class TagEntity(
    val id: String,
    val ownerId: String,
    val name: String,
    val version: Long = 1,
    val createdAt: String,
    val updatedAt: String
)

data class TaskTagEntity(
    val taskId: String,
    val tagId: String
)

data class FieldVersionEntity(
    val entityId: String,
    val fieldName: String,
    val version: Long,
    val value: String?,
    val updatedAt: String
)

data class SyncOperationEntity(
    val opId: String,
    val entityType: String,
    val entityId: String,
    val operationType: String,
    val scope: String,
    val fields: String,
    val createdAt: String,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val status: String = "pending"
)