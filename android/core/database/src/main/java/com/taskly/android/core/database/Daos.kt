package com.taskly.android.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE owner_id = :ownerId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun getAll(ownerId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE list_id = :listId AND deleted_at IS NULL ORDER BY updated_at DESC")
    fun getByList(listId: String): Flow<List<TaskEntity>>

    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String, updatedAt: String)

    @Query("SELECT * FROM tasks WHERE owner_id = :query OR name LIKE '%' || :query || '%'")
    suspend fun searchTasks(query: String): List<TaskEntity>
}

@Dao
interface ListDao {
    @Query("SELECT * FROM task_lists WHERE owner_id = :ownerId AND deleted_at IS NULL ORDER BY sort_order ASC")
    fun getAll(ownerId: String): Flow<List<ListEntity>>

    @Query("SELECT * FROM task_lists WHERE id = :id")
    suspend fun getById(id: String): ListEntity?

    @Upsert
    suspend fun upsertAll(lists: List<ListEntity>)

    @Upsert
    suspend fun upsert(list: ListEntity)

    @Query("UPDATE task_lists SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String, updatedAt: String)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE owner_id = :ownerId ORDER BY name ASC")
    fun getAll(ownerId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue WHERE status = 'pending' ORDER BY created_at ASC")
    suspend fun getPending(): List<SyncOperationEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'pending'")
    fun pendingCount(): Flow<Int>

    @Upsert
    suspend fun insert(op: SyncOperationEntity)

    @Query("DELETE FROM sync_queue WHERE opId = :opId")
    suspend fun remove(opId: String)

    @Query("UPDATE sync_queue SET status = :status, last_error = :error WHERE opId = :opId")
    suspend fun markFailed(opId: String, status: String = "failed", error: String? = null)

    @Query("DELETE FROM sync_queue WHERE status = 'completed'")
    suspend fun clearCompleted()

    @Query("SELECT * FROM sync_queue WHERE entity_id = :entityId ORDER BY created_at ASC")
    suspend fun getByEntity(entityId: String): List<SyncOperationEntity>
}

@Dao
interface FieldVersionDao {
    @Query("SELECT * FROM field_versions WHERE entity_id = :entityId ORDER BY field_name ASC")
    suspend fun getForEntity(entityId: String): List<FieldVersionEntity>

    @Upsert
    suspend fun upsertAll(versions: List<FieldVersionEntity>)

    @Query("SELECT * FROM field_versions WHERE entity_id = :entityId AND field_name = :fieldName")
    suspend fun getById(entityId: String, fieldName: String): FieldVersionEntity?
}