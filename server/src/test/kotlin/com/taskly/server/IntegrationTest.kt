package com.taskly.server

import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {

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
        val ctx = Database.ctx()
        ctx.execute("TRUNCATE TABLE tasks, lists, tags, task_tags, notes, reminders, refresh_tokens, users, processed_ops, change_log RESTART IDENTITY CASCADE")
    }

    @AfterAll
    fun teardown() {
        RedisService.close()
        Database.shutdown()
    }

    @Test
    fun `health endpoint returns 200`() = testApplication {
        application { moduleForTest() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ok"))
    }

    @Test
    fun `register and login flow`() = testApplication {
        application { moduleForTest() }

        val testEmail = "test-${System.currentTimeMillis()}@taskly.test"
        val password = "TestPassword123!"

        // Register
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegisterRequestMapper(testEmail, password)))
        }
        assertEquals(HttpStatusCode.Created, registerResp.status)
        val auth = json.decodeFromString<AuthResponseMapper>(registerResp.bodyAsText())
        assertNotNull(auth.accessToken)
        assertNotNull(auth.refreshToken)

        // Login
        val loginResp = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(LoginRequestMapper(testEmail, password)))
        }
        assertEquals(HttpStatusCode.OK, loginResp.status)
        val login = json.decodeFromString<AuthResponseMapper>(loginResp.bodyAsText())
        assertNotNull(login.accessToken)

        // Refresh token
        val refreshResp = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RefreshTokenRequestMapper(login.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, refreshResp.status)
    }

    @Test
    fun `create task and search`() = testApplication {
        application { moduleForTest() }

        val testEmail = "test-task-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegisterRequestMapper(testEmail, "Test123!")))
        }
        val auth = json.decodeFromString<AuthResponseMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        // Create list
        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Work"}""")
        }
        assertEquals(HttpStatusCode.Created, listResp.status)
        val listBody = json.decodeFromString<ListResponseMapper>(listResp.bodyAsText())
        val listId = listBody.id

        // Create task
        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Buy groceries","listId":"$listId","priority":1}""")
        }
        assertEquals(HttpStatusCode.Created, taskResp.status)
        val task = json.decodeFromString<TaskResponseMapper>(taskResp.bodyAsText())
        assertEquals("Buy groceries", task.name)
        assertEquals(1, task.priority)

        // Get task
        val getTaskResp = client.get("/v1/tasks/${task.id}") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, getTaskResp.status)

        // List tasks
        val listTasksResp = client.get("/v1/tasks") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, listTasksResp.status)

        // Update task
        val updateResp = client.patch("/v1/tasks/${task.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Buy organic groceries","version":${task.version}}""")
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)

        // Search
        val searchResp = client.get("/v1/tasks") {
            bearerAuth(token)
            parameter("q", "organic")
        }
        assertEquals(HttpStatusCode.OK, searchResp.status)

        // Complete
        val completeResp = client.post("/v1/tasks/${task.id}/complete") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, completeResp.status)

        // Delete
        val deleteResp = client.delete("/v1/tasks/${task.id}") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)
    }

    @Test
    fun `sync operation idempotency`() = testApplication {
        application { moduleForTest() }

        val testEmail = "test-sync-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegisterRequestMapper(testEmail, "Test123!")))
        }
        val auth = json.decodeFromString<AuthResponseMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        val taskId = "00000000-0000-0000-0000-000000000050"

        // Create task via sync
        val syncResp = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"ops":[{"opId":"op-test-1","scope":"user:${auth.userId}","entity":"task","entityId":"$taskId","type":"create","fields":{"name":"Test Task","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }
        assertEquals(HttpStatusCode.OK, syncResp.status)

        // Send same op_id again — idempotent
        val syncRetryResp = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"ops":[{"opId":"op-test-1","scope":"user:${auth.userId}","entity":"task","entityId":"$taskId","type":"create","fields":{"name":"Test Task","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }
        assertEquals(HttpStatusCode.OK, syncRetryResp.status)

        // Verify only one task
        val tasksResp = client.get("/v1/tasks") {
            bearerAuth(token)
        }
        val tasks = json.decodeFromString<PaginatedResponseMapper>(tasksResp.bodyAsText())
        val matching = tasks.data.filter { it.name == "Test Task" }
        assertEquals(1, matching.size, "Same op_id should not create duplicate")
    }

    @Test
    fun `smart add creates task with parsed fields`() = testApplication {
        application { moduleForTest() }

        val testEmail = "test-smartadd-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$testEmail","password":"Test123!","tz":"Europe/Moscow"}""")
        }
        assertEquals(HttpStatusCode.Created, registerResp.status)
        val auth = json.decodeFromString<AuthResponseMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        // Create list "Work"
        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Work"}""")
        }
        assertEquals(HttpStatusCode.Created, listResp.status)
        val listBody = json.decodeFromString<ListResponseMapper>(listResp.bodyAsText())

        // Create tag "test" — needed so tagNames lookup in SmartAddParser has data
        val tagResp = client.post("/v1/tags") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"test"}""")
        }
        assertEquals(HttpStatusCode.Created, tagResp.status)

        // Smart add: ^завтра #Work !1 Тестовая задача (tomorrow, Work list, high priority)
        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"^завтра #Work !1 Тестовая задача","smartAdd":true}""")
        }
        assertEquals(HttpStatusCode.Created, taskResp.status)
        val task = json.decodeFromString<TaskResponseMapper>(taskResp.bodyAsText())

        // Verify parsed fields
        assertEquals("Тестовая задача", task.name, "Clean name should have tokens stripped")
        assertNotNull(task.dueAt, "dueAt should be set from ^завтра")
        assertTrue(task.dueAt!!.contains("T"), "dueAt should have time component (end of day or specified)")
        assertEquals(1, task.priority, "priority should be 1 from !1")
        assertEquals(listBody.id, task.listId, "task should be in Work list from #Work")
        assertNotNull(task.createdAt)
    }

    @Test
    fun `recurring task creates next instance on complete`() = testApplication {
        application { moduleForTest() }

        val testEmail = "test-rec-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegisterRequestMapper(testEmail, "Test123!")))
        }
        val auth = json.decodeFromString<AuthResponseMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        // Create list
        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Recurring"}""")
        }
        val list = json.decodeFromString<ListResponseMapper>(listResp.bodyAsText())

        // Create weekly task
        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Weekly meeting","listId":"${list.id}","rrule":"FREQ=WEEKLY","repeatMode":"every"}""")
        }
        val task = json.decodeFromString<TaskResponseMapper>(taskResp.bodyAsText())

        // Complete
        val completeResp = client.post("/v1/tasks/${task.id}/complete") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, completeResp.status)
        val body = completeResp.bodyAsText()
        assertTrue(body.contains("nextInstance"), "Should create next instance: $body")
    }
}

// =============================================================
// Mapper DTOs — separate from server DTOs to avoid serialization
// dependency issues. These match the JSON shapes the API expects.
// =============================================================

@Serializable
data class RegisterRequestMapper(val email: String, val password: String, val tz: String? = null, val locale: String? = null)
@Serializable
data class LoginRequestMapper(val email: String, val password: String)
@Serializable
data class RefreshTokenRequestMapper(val refreshToken: String)
@Serializable
data class AuthResponseMapper(val accessToken: String, val refreshToken: String, val userId: String)
@Serializable
data class ListResponseMapper(val id: String, val name: String, val isSmart: Boolean = false, val query: String? = null, val color: String? = null, val sortOrder: Int = 0, val version: Long, val createdAt: String)
@Serializable
data class TaskResponseMapper(val id: String, val name: String, val listId: String, val priority: Int? = null, val dueAt: String? = null, val dueHasTime: Boolean = false, val startAt: String? = null, val estimateMin: Int? = null, val rrule: String? = null, val repeatMode: String? = null, val completedAt: String? = null, val url: String? = null, val tags: List<String>? = null, val version: Long, val createdAt: String, val updatedAt: String)
@Serializable
data class PaginatedResponseMapper(val data: List<TaskResponseMapper>, val cursor: String? = null)