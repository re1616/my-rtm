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

    @Query("SELECT * FROM tasks WHERE owner_id = :ownerId AND deleted_at IS NULL")
    suspend fun allLive(ownerId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE parent_id = :parentId AND deleted_at IS NULL ORDER BY created_at ASC")
    fun subtasks(parentId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    /** Full-text search (AND-S7): FTS4 match over names and notes, newest first. */
    @Query(
        """
        SELECT t.* FROM tasks t
        WHERE t.owner_id = :ownerId AND t.deleted_at IS NULL
          AND t.id IN (SELECT task_id FROM task_search WHERE task_search MATCH :match)
        ORDER BY t.updated_at DESC
        """
    )
    suspend fun search(ownerId: String, match: String): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM tasks WHERE owner_id = :ownerId")
    suspend fun count(ownerId: String): Int
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE task_id = :taskId ORDER BY created_at ASC")
    fun forTask(taskId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE task_id = :taskId ORDER BY created_at ASC")
    suspend fun forTaskNow(taskId: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE task_id = :taskId ORDER BY created_at ASC")
    fun forTask(taskId: String): Flow<List<AttachmentEntity>>

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations WHERE owner_id = :ownerId ORDER BY name ASC")
    fun getAll(ownerId: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE owner_id = :ownerId")
    suspend fun allNow(ownerId: String): List<LocationEntity>

    @Upsert
    suspend fun upsert(location: LocationEntity)

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts ORDER BY created_at ASC")
    fun observeAll(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM conflicts ORDER BY created_at ASC")
    suspend fun all(): List<ConflictEntity>

    @Upsert
    suspend fun upsert(conflict: ConflictEntity)

    @Query("DELETE FROM conflicts WHERE entity_id = :entityId AND field = :field")
    suspend fun delete(entityId: String, field: String)
}

@Dao
interface SearchDao {
    @Query("DELETE FROM task_search WHERE task_id = :taskId")
    suspend fun remove(taskId: String)

    @Query("INSERT INTO task_search (task_id, text) VALUES (:taskId, :text)")
    suspend fun insert(taskId: String, text: String)
}

data class TaskTagName(val taskId: String, val name: String)

@Dao
interface ListDao {
    @Query("SELECT * FROM task_lists WHERE owner_id = :ownerId AND deleted_at IS NULL")
    suspend fun allNow(ownerId: String): List<ListEntity>

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
    @Query("SELECT * FROM tags WHERE owner_id = :ownerId")
    suspend fun allNow(ownerId: String): List<TagEntity>

    @Query("SELECT * FROM tags WHERE owner_id = :ownerId AND lower(name) = lower(:name) LIMIT 1")
    suspend fun byName(ownerId: String, name: String): TagEntity?

    @Query("SELECT t.name FROM tags t JOIN task_tags tt ON tt.tag_id = t.id WHERE tt.task_id = :taskId ORDER BY t.name")
    suspend fun namesForTask(taskId: String): List<String>

    @Query("SELECT tt.task_id AS taskId, t.name AS name FROM task_tags tt JOIN tags t ON t.id = tt.tag_id")
    suspend fun allTaskTagNames(): List<TaskTagName>

    @Upsert
    suspend fun link(link: TaskTagEntity)

    @Query("DELETE FROM task_tags WHERE task_id = :taskId AND tag_id = :tagId")
    suspend fun unlink(taskId: String, tagId: String)

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
    @Query("SELECT * FROM sync_queue WHERE status = 'pending' ORDER BY rowid ASC")
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

    @Query("SELECT entity_id FROM sync_queue WHERE status = 'pending'")
    suspend fun queuedEntityIds(): List<String>
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