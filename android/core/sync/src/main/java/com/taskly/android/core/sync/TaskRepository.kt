package com.taskly.android.core.sync

import androidx.room.withTransaction
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Task mutations (AND-S2): every local write and the sync operation describing it are
 * committed in ONE Room transaction, so the queue can never miss or invent a change.
 */
class TaskRepository(
    private val db: TasklyDatabase,
    private val sync: SyncEngine,
) {
    suspend fun createTask(userId: String, name: String, listId: String = INBOX): TaskEntity {
        val now = Clock.System.now().toString()
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            ownerId = userId,
            listId = listId,
            name = name,
            createdAt = now,
            updatedAt = now,
        )
        db.withTransaction {
            db.taskDao().upsert(task)
            sync.enqueue(
                userId, "task", task.id, TaskOps.CREATE,
                buildJsonObject {
                    put("name", name)
                    // The server files unknown/local list ids (like the inbox placeholder) into Inbox
                    put("listId", listId)
                },
            )
        }
        return task
    }

    suspend fun setCompleted(task: TaskEntity, userId: String, completed: Boolean): TaskEntity {
        val now = Clock.System.now().toString()
        val updated = task.copy(completedAt = if (completed) now else null, updatedAt = now)
        db.withTransaction {
            db.taskDao().upsert(updated)
            if (completed) {
                sync.enqueue(userId, "task", task.id, TaskOps.COMPLETE, buildJsonObject { put("completedAt", now) })
            } else {
                sync.enqueue(userId, "task", task.id, TaskOps.UNCOMPLETE)
            }
        }
        return updated
    }

    suspend fun rename(task: TaskEntity, userId: String, name: String): TaskEntity {
        val now = Clock.System.now().toString()
        val updated = task.copy(name = name, updatedAt = now)
        db.withTransaction {
            db.taskDao().upsert(updated)
            sync.enqueue(userId, "task", task.id, TaskOps.UPDATE, buildJsonObject { put("name", name) })
        }
        return updated
    }

    companion object {
        /** Local placeholder until the server's Inbox id arrives with the first pull. */
        const val INBOX = "inbox"
    }
}
