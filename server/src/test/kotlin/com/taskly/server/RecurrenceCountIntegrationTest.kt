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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

/**
 * Integration test for COUNT (RFC 5545) via the API.
 *
 * Verifies SYNC-4: a task with RRULE COUNT=3 creates exactly 3 instances
 * after 3 completes, and a 4th complete does not produce a 4th instance.
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
    fun `COUNT=3 recurring task creates exactly 3 instances then stops`() = testApplication {
        application { moduleForTest() }

        val testEmail = "count-test-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegisterRequestMapper(testEmail, "Count123!")))
        }
        assertEquals(HttpStatusCode.OK, registerResp.status)
        val auth = json.decodeFromString<AuthResponseMapper>(registerResp.bodyAsText())
        val token = auth.accessToken

        // Create list
        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Count Test"}""")
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
        val list = json.decodeFromString<ListResponseMapper>(listResp.bodyAsText())

        // Create task with COUNT=3
        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"name":"Count3 task","listId":"${list.id}","rrule":"FREQ=DAILY;COUNT=3","repeatMode":"every"}""")
        }
        assertEquals(HttpStatusCode.OK, taskResp.status)
        val task = json.decodeFromString<TaskResponseMapper>(taskResp.bodyAsText())

        // Complete 3 times — each should produce a next instance
        var currentTask = task
        for (i in 1..3) {
            val completeResp = client.post("/v1/tasks/${currentTask.id}/complete") {
                bearerAuth(token)
            }
            assertEquals(HttpStatusCode.Created, completeResp.status, "Complete #$i should succeed")
            val body = completeResp.bodyAsText()
            assertTrue(body.contains("nextInstance"), "Complete #$i should create next instance: $body")
            val nextId = body.substringAfter("nextInstance\":\"").substringBefore("\"")
            assertNotNull(nextId, "nextInstance id should be present in complete #$i")
            assertTrue(nextId.isNotBlank(), "nextInstance id should not be blank in complete #$i")

            // Fetch the next instance
            val getResp = client.get("/v1/tasks/$nextId") {
                bearerAuth(token)
            }
            assertEquals(HttpStatusCode.OK, getResp.status)
            currentTask = json.decodeFromString<TaskResponseMapper>(getResp.bodyAsText())
        }

        // All 3 instances created. 4th complete should NOT produce another instance
        val finalResp = client.post("/v1/tasks/${currentTask.id}/complete") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, finalResp.status, "4th complete should not error")
        val finalBody = finalResp.bodyAsText()
        assertFalse(finalBody.contains("nextInstance"), "4th complete should NOT create next instance: $finalBody")
    }
}