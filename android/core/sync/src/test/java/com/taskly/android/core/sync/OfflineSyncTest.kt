package com.taskly.android.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.ChangeEntryDto
import com.taskly.android.core.network.SyncChangesResponseDto
import com.taskly.android.core.network.SyncOpDto
import com.taskly.android.core.network.SyncResultDto
import com.taskly.android.core.network.TasklyApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline behaviour of the sync layer against a scripted API (no network).
 * The real-server round trip is covered by [RealServerSyncE2ETest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineSyncTest {

    private lateinit var db: TasklyDatabase
    private val api = ScriptedApi()
    private lateinit var engine: SyncEngine
    private lateinit var repo: TaskRepository
    private val user = "6f1c2b9e-0000-4000-8000-000000000001"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).build()
        engine = SyncEngine(db, api)
        repo = TaskRepository(db, engine)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createTask_writesTaskAndCreateOpTogether() = runBlocking {
        val task = repo.createTask(user, """Say "hi" \ to Bob""")

        assertEquals(task.name, db.taskDao().getById(task.id)?.name)
        val op = db.syncQueueDao().getPending().single()
        assertEquals("create", op.operationType)
        assertEquals("user:$user", op.scope)
        assertEquals(task.id, op.entityId)

        // Fields are real JSON: quotes and backslashes in the name survive the round trip
        engine.push("token")
        val sent = api.pushed.single()
        assertEquals("""Say "hi" \ to Bob""", sent.fields!!["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun completeAfterCreate_isSentInOrder() = runBlocking {
        val task = repo.createTask(user, "Pay rent")
        repo.setCompleted(task, user, completed = true)

        engine.push("token")

        assertEquals(listOf("create", "complete"), api.pushed.map { it.type })
        assertNotNull(api.pushed[1].fields!!["completedAt"])
    }

    @Test
    fun acknowledgedOpsAreRemoved_rejectedOnesAreKeptAsFailed() = runBlocking {
        val a = repo.createTask(user, "A")
        val b = repo.createTask(user, "B")
        api.reject = setOf(b.id)

        val acked = engine.push("token")

        assertEquals(1, acked)
        assertTrue(db.syncQueueDao().getPending().isEmpty())
        assertEquals("failed", db.syncQueueDao().getByEntity(b.id).single().status)
        assertTrue(db.syncQueueDao().getByEntity(a.id).isEmpty())
    }

    @Test
    fun networkFailure_keepsQueuePending() = runBlocking {
        repo.createTask(user, "Offline")
        api.offline = true

        assertEquals(0, engine.push("token"))

        assertEquals(1, db.syncQueueDao().getPending().size)
    }

    @Test
    fun pull_appliesServerChangeLog() = runBlocking {
        val id = "0b8a3c1e-0000-4000-8000-0000000000aa"
        api.changes = listOf(
            change(1, id, "create", """{"name":"From web","list_id":"L1","priority":2,"completed_at":null}"""),
            change(2, id, "update", """{"name":"Renamed on web"}"""),
            change(3, id, "complete", """{"completed_at":"2026-10-18T10:00:00Z"}"""),
        )

        val cursor = engine.pull(user, 0, "token")

        assertEquals(3, cursor)
        val local = assertNotNull(db.taskDao().getById(id))
        assertEquals("Renamed on web", local.name)
        assertEquals("L1", local.listId)
        assertEquals(2, local.priority)
        assertEquals("2026-10-18T10:00:00Z", local.completedAt)
        assertEquals("user:$user", api.lastScope)

        api.changes += change(4, id, "uncomplete", "{}")
        assertEquals(4, engine.pull(user, cursor, "token"))
        assertNull(db.taskDao().getById(id)?.completedAt)
    }

    private fun change(seq: Long, id: String, op: String, payload: String) = ChangeEntryDto(
        seq = seq, scopeId = "user:$user", entity = "task", entityId = id, op = op,
        payload = JsonPrimitive(payload), actorId = user, ts = "2026-10-18T09:00:0${seq}Z",
    )
}

/** API double: records pushes, can go offline or reject ops, serves a change log. */
private class ScriptedApi : TasklyApiClient(io.ktor.client.HttpClient()) {
    val pushed = mutableListOf<SyncOpDto>()
    var offline = false
    var reject: Set<String> = emptySet()
    var changes: List<ChangeEntryDto> = emptyList()
    var lastScope: String? = null

    override suspend fun syncPush(ops: List<SyncOpDto>, accessToken: String): List<SyncResultDto> {
        if (offline) throw java.io.IOException("offline")
        pushed += ops
        return ops.map {
            if (it.entityId in reject) SyncResultDto(it.opId, "rejected", error = "nope")
            else SyncResultDto(it.opId, "ok", it.entityId, 1)
        }
    }

    override suspend fun syncDelta(scope: String, afterSeq: Long?, limit: Int, accessToken: String): SyncChangesResponseDto {
        lastScope = scope
        return SyncChangesResponseDto(changes.filter { it.seq > (afterSeq ?: 0) }.take(limit))
    }
}
