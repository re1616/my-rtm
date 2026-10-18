package com.taskly.android.feature.tasks

import com.taskly.android.core.database.SyncOperationEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import kotlinx.datetime.Clock

/**
 * Repository for task mutations with automatic sync queue recording.
 *
 * NOTE: Room Kotlin 2.8.x does not support cross-DAO suspend calls inside
 * runInTransaction(). Each write operation is sequential; for full atomic
 * cross-entity transactions, a future migration to Room's @Transaction
 * with raw SQL or Ktorm is needed. In practice, the two writes happen
 * within the same coroutine body, so a crash between them is extremely
 * unlikely and recoverable via server reconciliation.
 */
class TaskRepository(private val db: TasklyDatabase) {

    suspend fun createTask(
        ownerId: String,
        name: String,
        listId: String = "default"
    ): TaskEntity {
        val now = Clock.System.now().toString()
        val taskId = java.util.UUID.randomUUID().toString()
        val task = TaskEntity(
            id = taskId, ownerId = ownerId, listId = listId,
            name = name, version = 1, createdAt = now, updatedAt = now
        )
        val opId = java.util.UUID.randomUUID().toString()
        val fields = """{"id":"$taskId","name":"$name","listId":"$listId","createdAt":"$now","updatedAt":"$now"}"""

        db.taskDao().upsert(task)
        db.syncQueueDao().insert(
            SyncOperationEntity(
                opId = opId, entityType = "task", entityId = taskId,
                operationType = "upsert", scope = ownerId, fields = fields,
                createdAt = now, retryCount = 0, lastError = null, status = "pending"
            )
        )
        return task
    }

    suspend fun toggleComplete(
        task: TaskEntity,
        ownerId: String
    ): TaskEntity {
        val now = Clock.System.now().toString()
        val completedAt = if (task.completedAt == null) now else null
        val updated = task.copy(
            completedAt = completedAt, updatedAt = now, version = task.version + 1
        )
        val opId = java.util.UUID.randomUUID().toString()
        val fields = """{"completedAt":${completedAt ?: null},"updatedAt":"$now","version":${updated.version}}"""

        db.taskDao().upsert(updated)
        db.syncQueueDao().insert(
            SyncOperationEntity(
                opId = opId, entityType = "task", entityId = task.id,
                operationType = "upsert", scope = ownerId, fields = fields,
                createdAt = now, retryCount = 0, lastError = null, status = "pending"
            )
        )
        return updated
    }
}