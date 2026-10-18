package com.taskly.server.security

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
 * Security integration tests: SEC-1..SEC-7.
 *
 * SEC-1: Ownership — user can only access own resources.
 * SEC-2: Referential integrity — parent task references.
 * SEC-3: Server scope isolation — data is isolated per user.
 * SEC-4: Access matrix — owner/editor/viewer/outsider (basic in R1).
 * SEC-5: Session rotation — access token 15min expiry, refresh token rotation.
 * SEC-6: Rate limiting — per-IP and per-account limits (implemented in parent).
 * SEC-7: Feature flags endpoint (tested in FeaturesIntegrationTest).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityTest {

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
    fun `SEC-1 user cannot access another users task`() = testApplication {
        application { moduleForTest() }

        val emailA = "sec1-a-${System.currentTimeMillis()}@test.taskly"
        val regA = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailA","password":"Test123!"}""")
        }
        val userA = json.decodeFromString<AuthMapper>(regA.bodyAsText())

        val emailB = "sec1-b-${System.currentTimeMillis()}@test.taskly"
        val regB = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailB","password":"Test123!"}""")
        }
        val userB = json.decodeFromString<AuthMapper>(regB.bodyAsText())

        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(userA.accessToken)
            setBody("""{"name":"Secret"}""")
        }
        val listBody = json.decodeFromString<ListMapper>(listResp.bodyAsText())

        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(userA.accessToken)
            setBody("""{"name":"A private task","listId":"${listBody.id}"}""")
        }
        val taskBody = json.decodeFromString<TaskMapper>(taskResp.bodyAsText())

        val getResp = client.get("/v1/tasks/${taskBody.id}") {
            bearerAuth(userB.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, getResp.status,
            "SEC-1: User B should not see User A's task")
    }

    @Test
    fun `SEC-1 user cannot access another users list`() = testApplication {
        application { moduleForTest() }

        val emailA = "sec1-la-${System.currentTimeMillis()}@test.taskly"
        val regA = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailA","password":"Test123!"}""")
        }
        val userA = json.decodeFromString<AuthMapper>(regA.bodyAsText())

        val emailB = "sec1-lb-${System.currentTimeMillis()}@test.taskly"
        val regB = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailB","password":"Test123!"}""")
        }
        val userB = json.decodeFromString<AuthMapper>(regB.bodyAsText())

        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(userA.accessToken)
            setBody("""{"name":"Secret List"}""")
        }
        val listBody = json.decodeFromString<ListMapper>(listResp.bodyAsText())

        val getResp = client.get("/v1/lists/${listBody.id}") {
            bearerAuth(userB.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, getResp.status,
            "SEC-1: User B should not see User A's list")
    }

    @Test
    fun `SEC-3 data isolation between users`() = testApplication {
        application { moduleForTest() }

        val emailA = "sec3-a-${System.currentTimeMillis()}@test.taskly"
        val regA = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailA","password":"Test123!"}""")
        }
        val userA = json.decodeFromString<AuthMapper>(regA.bodyAsText())

        val emailB = "sec3-b-${System.currentTimeMillis()}@test.taskly"
        val regB = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailB","password":"Test123!"}""")
        }
        val userB = json.decodeFromString<AuthMapper>(regB.bodyAsText())

        // User A creates data
        client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(userA.accessToken)
            setBody("""{"name":"A's List"}""")
        }

        // User B lists — should not contain A's data
        val listsResp = client.get("/v1/lists") {
            bearerAuth(userB.accessToken)
        }
        assertFalse(listsResp.bodyAsText().contains("A's List"),
            "SEC-3: User B should not see User A's data")
    }

    @Test
    fun `SEC-4 basic access owner vs outsider`() = testApplication {
        application { moduleForTest() }

        val email = "sec4-${System.currentTimeMillis()}@test.taskly"
        val reg = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val user = json.decodeFromString<AuthMapper>(reg.bodyAsText())

        // Owner creates list
        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.accessToken)
            setBody("""{"name":"Owner List"}""")
        }
        assertEquals(HttpStatusCode.Created, listResp.status)
        val listBody = json.decodeFromString<ListMapper>(listResp.bodyAsText())

        // Owner creates task
        val taskResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.accessToken)
            setBody("""{"name":"Owner Task","listId":"${listBody.id}"}""")
        }
        assertEquals(HttpStatusCode.Created, taskResp.status)
        val taskBody = json.decodeFromString<TaskMapper>(taskResp.bodyAsText())

        // Owner reads
        val readResp = client.get("/v1/tasks/${taskBody.id}") {
            bearerAuth(user.accessToken)
        }
        assertEquals(HttpStatusCode.OK, readResp.status)

        // Owner updates
        val updateResp = client.patch("/v1/tasks/${taskBody.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.accessToken)
            setBody("""{"name":"Updated","version":${taskBody.version}}""")
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)

        // Owner completes
        val completeResp = client.post("/v1/tasks/${taskBody.id}/complete") {
            bearerAuth(user.accessToken)
        }
        assertEquals(HttpStatusCode.OK, completeResp.status)

        // Unauthenticated outsider is rejected (401 via auth middleware)
        val unauthResp = client.get("/v1/tasks/${taskBody.id}")
        assertEquals(HttpStatusCode.Unauthorized, unauthResp.status,
            "SEC-4: Unauthenticated access should be rejected")
    }

    @Test
    fun `SEC-5 refresh token rotation`() = testApplication {
        application { moduleForTest() }

        val email = "sec5-${System.currentTimeMillis()}@test.taskly"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        assertEquals(HttpStatusCode.Created, registerResp.status)
        val auth = json.decodeFromString<AuthMapper>(registerResp.bodyAsText())
        assertNotNull(auth.refreshToken, "SEC-5: Should get refresh token on register")

        val originalRefresh = auth.refreshToken

        // Access token works
        val tasksResp = client.get("/v1/tasks") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, tasksResp.status)

        // Refresh
        val refreshResp = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$originalRefresh"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshResp.status)
        val refreshed = json.decodeFromString<AuthMapper>(refreshResp.bodyAsText())

        // New access token works
        val tasksAfter = client.get("/v1/tasks") {
            bearerAuth(refreshed.accessToken)
        }
        assertEquals(HttpStatusCode.OK, tasksAfter.status)

        // Old refresh token revoked (rotation)
        val oldRefreshResp = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$originalRefresh"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, oldRefreshResp.status,
            "SEC-5: Old refresh token should be revoked after rotation")
    }

    @Test
    fun `SEC-5 invalid access token rejected`() = testApplication {
        application { moduleForTest() }
        val resp = client.get("/v1/tasks") {
            bearerAuth("invalid.token.here")
        }
        // Auth middleware returns 401 for invalid tokens
        assertEquals(HttpStatusCode.Unauthorized, resp.status,
            "SEC-5: Invalid access token should be rejected")
    }

    @Test
    fun `SEC-5 missing auth header rejected`() = testApplication {
        application { moduleForTest() }
        val resp = client.get("/v1/tasks")
        assertEquals(HttpStatusCode.Unauthorized, resp.status,
            "SEC-5: Missing auth header should be rejected")
    }

    // DTOs
    @Serializable
    data class AuthMapper(val accessToken: String, val refreshToken: String? = null, val userId: String)
    @Serializable
    data class ListMapper(val id: String, val name: String, val isSmart: Boolean = false, val version: Long, val createdAt: String)
    @Serializable
    data class TaskMapper(val id: String, val name: String, val listId: String, val version: Long, val createdAt: String, val updatedAt: String)
}