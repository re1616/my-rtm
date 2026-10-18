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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

/**
 * Integration test for COUNT (RFC 5545) via the API.
 *
 * Verifies SYNC-4: a task with RRULE COUNT=3 has exactly 3 instances in total — completing
 * the 1st and 2nd creates the next one, completing the 3rd ends the series.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecurrenceCountIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @BeforeAll
    fun setup() {
        check(Config.database.database.endsWith("_test")) {
            "Integration tests must use a *_test database (PGDATABASE)"
        }
        Database.init(Config.database)
        RedisService.init(Config.redis)
        RateLimiter.init(Config.rateLimit)
        AuthService.init(Config.auth)
    }

    @AfterAll
    fun teardown() {
        Database.shutdown()
        RedisService.close()
    }

    @Test
    fun `COUNT=3 recurring task yields exactly 3 instances`() = testApplication {
        application { moduleForTest() }

        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegisterRequestMapper("count-test-${System.nanoTime()}@taskly.test", "Count123!")))
        }
        assertEquals(HttpStatusCode.Created, registerResp.status)
        val token = json.decodeFromString<AuthResponseMapper>(registerResp.bodyAsText()).accessToken

        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Count Test"}""")
        }
        assertEquals(HttpStatusCode.Created, listResp.status)
        val list = json.decodeFromString<ListResponseMapper>(listResp.bodyAsText())

        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Count3 task","listId":"${list.id}","dueAt":"2026-11-02T09:00:00Z","rrule":"FREQ=DAILY;COUNT=3","repeatMode":"every"}""")
        }
        assertEquals(HttpStatusCode.Created, taskResp.status)
        var current = json.decodeFromString<TaskResponseMapper>(taskResp.bodyAsText())

        // Instances 1 and 2: completing each one creates the next instance of the series.
        for (i in 1..2) {
            val next = complete(current.id, token)
            assertNotNull(next, "Completing instance #$i must create instance #${i + 1}")
            assertEquals("FREQ=DAILY;COUNT=${3 - i}", next.rrule, "Instance #${i + 1} keeps the remaining count")
            current = next
        }

        // Instance 3 is the last one: completing it ends the series.
        assertNull(complete(current.id, token), "COUNT=3 means 3 instances; a 4th must not be created")

        // No open instance of the series is left in the list.
        val tasks = client.get("/v1/tasks?list_id=${list.id}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, tasks.status)
        val names = json.decodeFromString<PaginatedResponseMapper>(tasks.bodyAsText()).data.filter { it.name == "Count3 task" }
        assertTrue(names.all { it.completedAt != null }, "No open instance may remain: $names")
    }

    /** Completes [taskId] and returns the next instance, or null when the series has ended. */
    private suspend fun ApplicationTestBuilder.complete(taskId: String, token: String): TaskResponseMapper? {
        val resp = client.post("/v1/tasks/$taskId/complete") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val next = json.parseToJsonElement(resp.bodyAsText()).jsonObject["nextInstance"]
        return if (next == null || next is JsonNull) null else json.decodeFromJsonElement(TaskResponseMapper.serializer(), next)
    }
}
