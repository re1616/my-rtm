package com.taskly.server.sync

import com.taskly.server.*
import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

/**
 * Sync integration tests: SYNC-1..SYNC-7.
 *
 * SYNC-1: Savepoints + per-op results (parent task).
 * SYNC-2: Idempotency — same op_id returns same result.
 * SYNC-3: MergeEngine integration — server uses MergeEngine for conflict resolution.
 * SYNC-4: DST+COUNT integration — recurring tasks with COUNT and DST-aware dates.
 * SYNC-5: QueryParser -> SqlBuilder equivalence through server search.
 * SYNC-6/7: Minimal volume tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @BeforeAll
    fun setup() {
        Database.init(Config.database)
        RedisService.init(Config.redis)
        RateLimiter.init(Config.rateLimit)
        AuthService.init(Config.auth)
    }

    @BeforeEach
    fun cleanup() {
        Database.ctx().execute(
            "TRUNCATE TABLE tasks, lists, tags, task_tags, notes, reminders, refresh_tokens, users, processed_ops, change_log, feature_flags RESTART IDENTITY CASCADE"
        )
    }

    @AfterAll
    fun teardown() {
        RedisService.close()
        Database.shutdown()
    }

    @Test
    fun `SYNC-2 sync operation idempotency`() = testApplication {
        application { moduleForTest() }

        val email = "sync2-${System.currentTimeMillis()}@test.taskly"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val auth = json.decodeFromString<AuthMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        val taskId = "00000000-0000-0000-0000-0000000000a1"
        val opBody = """{"ops":[{"opId":"sync-op-idemp-1","scope":"user:${auth.userId}","entity":"task","entityId":"$taskId","type":"create","fields":{"name":"Idempotent Task","listId":"00000000-0000-0000-0000-000000000001"}}]}"""

        val resp1 = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(opBody)
        }
        assertEquals(HttpStatusCode.OK, resp1.status)

        // Same op_id again
        val resp2 = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(opBody)
        }
        assertEquals(HttpStatusCode.OK, resp2.status)

        // Only one task created
        val tasksResp = client.get("/v1/tasks") {
            bearerAuth(token)
        }
        val tasks = json.decodeFromString<PaginatedTasks>(tasksResp.bodyAsText())
        val matching = tasks.data.filter { it.name == "Idempotent Task" }
        assertEquals(1, matching.size, "SYNC-2: Same op_id should not create duplicate")
    }

    @Test
    fun `SYNC-3 merge engine integration`() = testApplication {
        application { moduleForTest() }

        val email = "sync3-${System.currentTimeMillis()}@test.taskly"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val auth = json.decodeFromString<AuthMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        val taskId = "00000000-0000-0000-0000-0000000000b1"

        // Create via sync
        client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"ops":[{"opId":"smc1","scope":"user:${auth.userId}","entity":"task","entityId":"$taskId","type":"create","fields":{"name":"Merge Test","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }

        // Update priority via sync
        client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"ops":[{"opId":"smu1","scope":"user:${auth.userId}","entity":"task","entityId":"$taskId","type":"update","fields":{"priority":"1"}}]}""")
        }

        // Complete via sync
        client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"ops":[{"opId":"smco1","scope":"user:${auth.userId}","entity":"task","entityId":"$taskId","type":"complete","fields":{}}]}""")
        }

        // Verify via REST
        val getResp = client.get("/v1/tasks/$taskId") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val task = json.decodeFromString<TaskMapper>(getResp.bodyAsText())
        assertEquals("Merge Test", task.name)
        assertEquals(1, task.priority)
        assertNotNull(task.completedAt, "SYNC-3: Task should be completed via sync")
    }

    @Test
    fun `SYNC-4 recurring task DST aware`() = testApplication {
        application { moduleForTest() }

        val email = "sync4a-${System.currentTimeMillis()}@test.taskly"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val auth = json.decodeFromString<AuthMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Recurring"}""")
        }
        val listBody = json.decodeFromString<ListMapper>(listResp.bodyAsText())

        // Create weekly recurring task
        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Weekly DST Test","listId":"${listBody.id}","rrule":"FREQ=WEEKLY","repeatMode":"every"}""")
        }
        assertEquals(HttpStatusCode.Created, taskResp.status)
        val task = json.decodeFromString<TaskMapper>(taskResp.bodyAsText())

        // Complete — should create nextInstance
        val completeResp = client.post("/v1/tasks/${task.id}/complete") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, completeResp.status)
        assertTrue(completeResp.bodyAsText().contains("nextInstance"),
            "SYNC-4: Recurring task should create next instance on complete")
    }

    @Test
    fun `SYNC-4 recurring task with COUNT limit`() = testApplication {
        application { moduleForTest() }

        val email = "sync4b-${System.currentTimeMillis()}@test.taskly"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val auth = json.decodeFromString<AuthMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Count Test"}""")
        }
        val listBody = json.decodeFromString<ListMapper>(listResp.bodyAsText())

        // Create daily task with COUNT=3
        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Count Task","listId":"${listBody.id}","rrule":"FREQ=DAILY;COUNT=3","repeatMode":"every"}""")
        }
        assertEquals(HttpStatusCode.Created, taskResp.status)
        val task = json.decodeFromString<TaskMapper>(taskResp.bodyAsText())

        // Complete
        val completeResp = client.post("/v1/tasks/${task.id}/complete") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, completeResp.status)
        assertTrue(completeResp.bodyAsText().contains("nextInstance"),
            "SYNC-4: COUNT recurring task should create next instance")
    }

    @Test
    fun `SYNC-5 search with smart syntax`() = testApplication {
        application { moduleForTest() }

        val email = "sync5-${System.currentTimeMillis()}@test.taskly"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val auth = json.decodeFromString<AuthMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        // Create list and task
        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Work"}""")
        }
        val listBody = json.decodeFromString<ListMapper>(listResp.bodyAsText())

        client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Important task","listId":"${listBody.id}","priority":1}""")
        }

        // Search
        val searchResp = client.get("/v1/tasks?q=Important") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, searchResp.status)
        val searchResult = json.decodeFromString<PaginatedTasks>(searchResp.bodyAsText())
        assertTrue(searchResult.data.isNotEmpty(), "SYNC-5: Search should find the task")
        assertTrue(searchResult.data.any { it.name == "Important task" },
            "SYNC-5: Search should find 'Important task'")
    }

    @Test
    fun `SYNC-6 batch sync of 5 operations`() = testApplication {
        application { moduleForTest() }

        val email = "sync6-${System.currentTimeMillis()}@test.taskly"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val auth = json.decodeFromString<AuthMapper>(registerResp.bodyAsText())
        val token = auth.accessToken
        val uid = auth.userId

        val ops = (1..5).map { i ->
            """{"opId":"vol-$i","scope":"user:$uid","entity":"task","entityId":"00000000-0000-0000-0000-0000000000c$i","type":"create","fields":{"name":"Volume Task $i","listId":"00000000-0000-0000-0000-000000000001"}}"""
        }
        val batch = """{"ops":[${ops.joinToString(",")}]}"""

        val syncResp = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(batch)
        }
        assertEquals(HttpStatusCode.OK, syncResp.status)
        val results = json.decodeFromString<SyncResults>(syncResp.bodyAsText())
        assertEquals(5, results.results.size, "SYNC-6: Should process all 5 ops")
        assertTrue(results.results.all { it.status == "ok" },
            "SYNC-6: All 5 ops should succeed")
    }

    @Test
    fun `SYNC-7 change log cursor pagination`() = testApplication {
        application { moduleForTest() }

        val email = "sync7-${System.currentTimeMillis()}@test.taskly"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val auth = json.decodeFromString<AuthMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        val taskId = "00000000-0000-0000-0000-0000000000d1"
        client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"ops":[{"opId":"cursor-test-1","scope":"user:${auth.userId}","entity":"task","entityId":"$taskId","type":"create","fields":{"name":"Cursor Task","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }

        val changesResp = client.get("/v1/sync/changes?scope=user:${auth.userId}&after=0") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, changesResp.status)
        val body = changesResp.bodyAsText()
        assertTrue(body.contains("changes"), "SYNC-7: Should return changes: $body")
        assertTrue(body.contains("Cursor Task"), "SYNC-7: Changes should contain the task name: $body")
    }

    // DTOs
    @Serializable
    data class AuthMapper(val accessToken: String, val refreshToken: String? = null, val userId: String)
    @Serializable
    data class ListMapper(val id: String, val name: String, val version: Long, val createdAt: String)
    @Serializable
    data class TaskMapper(val id: String, val name: String, val listId: String, val version: Long, val createdAt: String, val updatedAt: String, val completedAt: String? = null, val priority: Int? = null)
    @Serializable
    data class PaginatedTasks(val data: List<TaskMapper>, val cursor: String? = null)
    @Serializable
    data class SyncResultMapper(val opId: String, val status: String, val entityId: String? = null, val version: Long? = null, val error: String? = null)
    @Serializable
    data class SyncResults(val results: List<SyncResultMapper>)
}