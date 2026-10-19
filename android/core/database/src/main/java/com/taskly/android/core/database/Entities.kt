package com.taskly.android.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
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
    @ColumnInfo(name = "location_id") val locationId: String? = null,
    val version: Long = 1,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
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
    primaryKeys = ["task_id", "tag_id"]
)
data class TaskTagEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "tag_id") val tagId: String
)

@Entity(
    tableName = "field_versions",
    primaryKeys = ["entity_id", "field_name"]
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
    val status: String = "pending",
    /** Version the edit was based on (notes; AND-S5 conflict detection). */
    @ColumnInfo(name = "base_version") val baseVersion: Long? = null,
)

/** Markdown notes of a task (WEB-F3 / AND parity), synced as their own entity. */
@Entity(tableName = "notes", indices = [Index(value = ["task_id"], name = "index_notes_task_id")])
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val content: String,
    val version: Long = 1,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

/** Link attachments (http/https only). */
@Entity(tableName = "attachments", indices = [Index(value = ["task_id"], name = "index_attachments_task_id")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val name: String,
    val url: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

/**
 * A field where the server kept its own value over a local edit (AND-S5). Shown as a dialog
 * for the task name and notes: keep mine (re-sent) or take the server's.
 */
@Entity(tableName = "conflicts", primaryKeys = ["entity_id", "field"])
data class ConflictEntity(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val field: String,
    @ColumnInfo(name = "local_value") val localValue: String?,
    @ColumnInfo(name = "server_value") val serverValue: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

/**
 * Offline full-text search (AND-S7) over task names and notes. Platform SQLite on Android has
 * FTS3/FTS4 (FTS5 is not compiled in), so this is an FTS4 table kept up to date by the
 * repository and the sync engine. unicode61 folds case for Cyrillic too.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "task_search")
data class TaskSearchEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    val text: String,
)
