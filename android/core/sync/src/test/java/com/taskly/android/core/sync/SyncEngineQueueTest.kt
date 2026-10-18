package com.taskly.android.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-S2: offline mutation → sync queue in one step.
 * Simulates "task created offline": the task is written to Room and the
 * operation is queued; without a network the queue keeps the op pending.
 *
 * Runs on the JVM via Robolectric (CI-safe, no emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineQueueTest {

    private lateinit var database: TasklyDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun offlineTaskCreate_enqueuesPendingSyncOp() = runBlocking {
        val userId = "user-1"
        val taskId = "task-offline-1"
        val now = "2026-10-18T10:00:00Z"

        // Local write (the app's normal mutation path)
        database.taskDao().upsert(
            TaskEntity(
                id = taskId,
                ownerId = userId,
                listId = "default",
                name = "Offline task",
                createdAt = now,
                updatedAt = now
            )
        )

        // Queue the operation next to the write
        SyncEngine.enqueueOperation(
            db = database,
            entityType = "task",
            entityId = taskId,
            operationType = "create",
            fields = mapOf("name" to "Offline task", "createdAt" to now, "updatedAt" to now),
            userId = userId
        )

        val pending = database.syncQueueDao().getPending()
        assertEquals(1, pending.size)
        val op = pending.first()
        assertEquals(taskId, op.entityId)
        assertEquals("task", op.entityType)
        assertEquals("create", op.operationType)
        assertEquals("pending", op.status)
        assertTrue(op.fields.contains("\"name\""))

        // Verify the task is visible locally even though it was never pushed
        val local = database.taskDao().getById(taskId)
        assertEquals("Offline task", local?.name)
    }

    @Test
    fun multipleOfflineMutations_queueInOrder() = runBlocking {
        val userId = "user-1"

        for (i in 1..3) {
            SyncEngine.enqueueOperation(
                db = database,
                entityType = "task",
                entityId = "task-$i",
                operationType = "create",
                fields = mapOf("name" to "Task $i"),
                userId = userId
            )
        }

        val pending = database.syncQueueDao().getPending()
        assertEquals(3, pending.size)
        assertEquals(listOf("task-1", "task-2", "task-3"), pending.map { it.entityId })
    }
}