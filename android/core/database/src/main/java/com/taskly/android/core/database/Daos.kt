package com.taskly.android.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DAO interfaces — Room annotations will be added in coder phase (AND-S1).
 * Phase 1 scaffold: in-memory stubs.
 */

class TaskDao {
    private val _tasks = MutableStateFlow<List<TaskEntity>>(emptyList())

    fun getAll(ownerId: String): Flow<List<TaskEntity>> = _tasks

    fun getByList(listId: String): Flow<List<TaskEntity>> = _tasks

    suspend fun upsertAll(tasks: List<TaskEntity>) { _tasks.value = tasks }

    suspend fun upsert(task: TaskEntity) { /* stub */ }

    suspend fun softDelete(id: String, deletedAt: String, updatedAt: String) { /* stub */ }
}

class ListDao {
    private val _lists = MutableStateFlow<List<ListEntity>>(emptyList())

    fun getAll(ownerId: String): Flow<List<ListEntity>> = _lists

    suspend fun upsertAll(lists: List<ListEntity>) { _lists.value = lists }
}

class TagDao {
    private val _tags = MutableStateFlow<List<TagEntity>>(emptyList())

    fun getAll(ownerId: String): Flow<List<TagEntity>> = _tags
}

class SyncQueueDao {
    private val _queue = mutableListOf<SyncOperationEntity>()

    suspend fun getPending(): List<SyncOperationEntity> = _queue.filter { it.status == "pending" }

    fun pendingCount(): Flow<Int> = MutableStateFlow(_queue.size)

    suspend fun insert(op: SyncOperationEntity) { _queue.add(op) }

    suspend fun markFailed(opId: String, status: String = "failed", error: String? = null) { /* stub */ }

    suspend fun remove(opId: String) { _queue.removeAll { it.opId == opId } }

    suspend fun clearCompleted() { _queue.removeAll { it.status == "completed" } }
}

class FieldVersionDao {
    private val _versions = mutableListOf<FieldVersionEntity>()

    suspend fun getForEntity(entityId: String): List<FieldVersionEntity> = _versions.filter { it.entityId == entityId }

    suspend fun upsertAll(versions: List<FieldVersionEntity>) { /* stub */ }
}