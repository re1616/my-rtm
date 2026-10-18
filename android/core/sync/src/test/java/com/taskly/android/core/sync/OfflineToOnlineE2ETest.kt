package com.taskly.android.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskly.android.core.database.SyncOperationEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.SyncOpDto
import com.taskly.android.core.network.SyncResultDto
import com.taskly.android.core.network.SyncChangesResponseDto
import com.taskly.android.core.network.TasklyApiClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E2E test: offline-created task reaches the server after "connection".
 *
 * Criteria 5: uses real SyncEngine + fake TasklyApiClient to prove:
 * 1. Task + queue entry are written (offline).
 * 2. After "connecting" (calling processQueueWithClient), the task is sent.
 * 3. Server-confirmed ops are removed from the queue.
 * 4. Network failures leave the queue intact for retry.
 *
 * Runs on JVM via Robolectric (CI-safe, no emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineToOnlineE2ETest {

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
    fun offlineCreate_taskQueued_thenPushedToServer() = runBlocking {
        val userId = "e2e-user"
        val taskId = "e2e-task-offline-1"
        val now = "2026-10-18T12:00:00Z"

        // Phase 1: Offline create (simulates TaskRepository.createTask)
        database.taskDao().upsert(
            TaskEntity(id = taskId, ownerId = userId, listId = "default",
                name = "E2E Offline task", createdAt = now, updatedAt = now)
        )
        database.syncQueueDao().insert(
            SyncOperationEntity(opId = "e2e-op-1", entityType = "task", entityId = taskId,
                operationType = "upsert", scope = userId,
                fields = """{"id":"$taskId","name":"E2E Offline task"}""",
                createdAt = now, retryCount = 0, lastError = null, status = "pending")
        )

        // Verify task visible offline
        val localTask = database.taskDao().getById(taskId)
        checkNotNull(localTask, "Task must exist offline")
        checkEquals("E2E Offline task", localTask?.name ?: "", "Task name mismatch")

        // Verify queue has 1 pending op
        val pendingBefore = database.syncQueueDao().getPending()
        checkEquals(1, pendingBefore.size, "Expected 1 pending op before sync")
        checkEquals(taskId, pendingBefore.first().entityId, "Queue entityId mismatch")

        // Phase 2: Push queue via fake client that "acknowledges" the op
        val fakeClient = FakeApiClient { opIds ->
            opIds.map { SyncResultDto(opId = it, status = "ok", entityId = taskId, version = 2L) }
        }

        val processed = SyncEngine.processQueueWithClient(database, "test-access-token", fakeClient)
        checkEquals(1, processed, "Expected 1 processed op")

        // Queue is now empty
        val pendingAfter = database.syncQueueDao().getPending()
        checkEquals(0, pendingAfter.size, "Queue must be empty after successful push")

        // Fake client received the op
        checkEquals(1, fakeClient.pushedOps.size, "Fake client must receive 1 op")
        checkEquals(taskId, fakeClient.pushedOps.first()?.entityId ?: "", "Op entityId mismatch")
        checkEquals("upsert", fakeClient.pushedOps.first()?.type ?: "", "Op type mismatch")
    }

    @Test
    fun offlineCreate_withoutNetwork_queuePersists() = runBlocking {
        val userId = "e2e-user-2"
        val taskId = "e2e-task-offline-2"
        val now = "2026-10-18T12:30:00Z"

        database.taskDao().upsert(
            TaskEntity(id = taskId, ownerId = userId, listId = "default",
                name = "Persistent offline task", createdAt = now, updatedAt = now)
        )
        database.syncQueueDao().insert(
            SyncOperationEntity(opId = "e2e-op-2", entityType = "task", entityId = taskId,
                operationType = "upsert", scope = userId,
                fields = """{"name":"Persistent offline task"}""",
                createdAt = now, retryCount = 0, lastError = null, status = "pending")
        )

        // Fake that throws (simulates network down)
        val failingClient = FakeApiClient { _ -> throw Exception("Simulated network failure") }

        val processed = SyncEngine.processQueueWithClient(database, "test-token", failingClient)
        checkEquals(0, processed, "Expected 0 processed ops on failure")

        // Queue entry must still be pending
        val pending = database.syncQueueDao().getPending()
        checkEquals(1, pending.size, "Queue must preserve ops on network failure")
        checkEquals(taskId, pending.first().entityId, "Queue entityId must survive failure")
    }

    // Assertion helpers (avoid requiring JUnit assertions with nullable args)

    private fun checkNotNull(obj: Any?, message: String) {
        if (obj == null) throw AssertionError(message)
    }

    private fun checkEquals(expected: Any, actual: Any, message: String) {
        if (expected != actual) throw AssertionError("$message — expected '$expected' but got '$actual'")
    }
}

/**
 * A fake TasklyApiClient that records pushed ops and returns caller-defined results.
 * Does not make real HTTP calls.
 */
class FakeApiClient(
    private val handler: (List<String>) -> List<SyncResultDto>
) : TasklyApiClient(
    io.ktor.client.HttpClient()
) {
    var pushedOps: List<SyncOpDto> = emptyList()
        private set

    override suspend fun syncPush(
        ops: List<SyncOpDto>,
        accessToken: String
    ): List<SyncResultDto> {
        pushedOps = ops
        return handler(ops.map { it.opId })
    }

    override suspend fun syncDelta(
        scope: String,
        afterSeq: Long?,
        limit: Int,
        accessToken: String
    ): SyncChangesResponseDto {
        return SyncChangesResponseDto(changes = emptyList(), cursor = null)
    }
}