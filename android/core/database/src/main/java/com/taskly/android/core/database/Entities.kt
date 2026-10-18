package com.taskly.android.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    val name: String,
    val priority: Int? = null,
    @ColumnInfo(name = "due_at") val dueAt: String? = null,
    @ColumnInfo(name = "due_has_time") val dueHasTime: Boolean = false,
    @ColumnInfo(name = "start_at") val startAt: String? = null,
    @ColumnInfo(name = "estimate_min") val estimateMin: Int? = null,
    val rrule: String? = null,
    @ColumnInfo(name = "repeat_mode") val repeatMode: String? = null,
    @ColumnInfo(name = "completed_at") val completedAt: String? = null,
    @ColumnInfo(name = "postponed_count") val postponedCount: Int = 0,
    val url: String? = null,
    val version: Long = 1,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(tableName = "task_lists")
data class ListEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val name: String,
    @ColumnInfo(name = "is_smart") val isSmart: Boolean = false,
    val query: String? = null,
    val color: String? = null,
    val archived: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    val version: Long = 1,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val name: String,
    val version: Long = 1,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(
    tableName = "task_tags",
    primaryKeys = ["taskId", "tagId"]
)
data class TaskTagEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "tag_id") val tagId: String
)

@Entity(
    tableName = "field_versions",
    primaryKeys = ["entityId", "fieldName"]
)
data class FieldVersionEntity(
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "field_name") val fieldName: String,
    val version: Long,
    val value: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

@Entity(tableName = "sync_queue")
data class SyncOperationEntity(
    @PrimaryKey val opId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "operation_type") val operationType: String,
    val scope: String,
    val fields: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    val status: String = "pending"
)