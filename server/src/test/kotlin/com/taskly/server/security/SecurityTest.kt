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

    // ── SEC-2: Reference checks ──

    @Test
    fun `SEC-2 foreign parent_id in sync create is rejected`() = testApplication {
        application { moduleForTest() }

        val emailA = "sec2a-${System.currentTimeMillis()}@test.taskly"
        val regA = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailA","password":"Test123!"}""")
        }
        val userA = json.decodeFromString<AuthMapper>(regA.bodyAsText())

        val emailB = "sec2b-${System.currentTimeMillis()}@test.taskly"
        val regB = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailB","password":"Test123!"}""")
        }
        val userB = json.decodeFromString<AuthMapper>(regB.bodyAsText())

        // User A creates a task
        val taskIdA = "00000000-0000-0000-0000-0000000000e1"
        client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(userA.accessToken)
            setBody("""{"ops":[{"opId":"sec2-a1","scope":"user:${userA.userId}","entity":"task","entityId":"$taskIdA","type":"create","fields":{"name":"A's Task","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }

        // User B tries to create a task with parent_id pointing to A's task
        val taskIdB = "00000000-0000-0000-0000-0000000000a2"
        val resp = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(userB.accessToken)
            setBody("""{"ops":[{"opId":"sec2-b1","scope":"user:${userB.userId}","entity":"task","entityId":"$taskIdB","type":"create","fields":{"name":"B's Task","parentId":"$taskIdA","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }
        val body = json.decodeFromString<SyncResults>(resp.bodyAsText())
        assertEquals("forbidden", body.results[0].status,
            "SEC-2: Foreign parent_id should be rejected with forbidden")
    }

    @Test
    fun `SEC-2 foreign location_id in sync is rejected`() = testApplication {
        application { moduleForTest() }

        val emailA = "sec2c-${System.currentTimeMillis()}@test.taskly"
        val regA = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailA","password":"Test123!"}""")
        }
        val userA = json.decodeFromString<AuthMapper>(regA.bodyAsText())

        val emailB = "sec2d-${System.currentTimeMillis()}@test.taskly"
        val regB = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailB","password":"Test123!"}""")
        }
        val userB = json.decodeFromString<AuthMapper>(regB.bodyAsText())

        // User A creates a location
        val locId = "00000000-0000-0000-0000-0000000000f1"
        client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(userA.accessToken)
            setBody("""{"ops":[{"opId":"sec2-loc1","scope":"user:${userA.userId}","entity":"location","entityId":"$locId","type":"create","fields":{"name":"Home"}}]}""")
        }

        // User B tries to create a task referencing A's location
        val taskId = "00000000-0000-0000-0000-0000000000f2"
        val resp = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(userB.accessToken)
            setBody("""{"ops":[{"opId":"sec2-loc2","scope":"user:${userB.userId}","entity":"task","entityId":"$taskId","type":"create","fields":{"name":"B's Task","locationId":"$locId","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }
        val body = json.decodeFromString<SyncResults>(resp.bodyAsText())
        assertEquals("forbidden", body.results[0].status,
            "SEC-2: Foreign location_id should be rejected")
    }

    @Test
    fun `SEC-2 own parent_id in sync is accepted`() = testApplication {
        application { moduleForTest() }

        val email = "sec2e-${System.currentTimeMillis()}@test.taskly"
        val reg = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val user = json.decodeFromString<AuthMapper>(reg.bodyAsText())

        // Create parent task
        val parentId = "00000000-0000-0000-0000-0000000cafe1"
        client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.accessToken)
            setBody("""{"ops":[{"opId":"sec2-p1","scope":"user:${user.userId}","entity":"task","entityId":"$parentId","type":"create","fields":{"name":"Parent","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }

        // Create child task with own parent_id
        val childId = "00000000-0000-0000-0000-0000000cafe2"
        val resp = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.accessToken)
            setBody("""{"ops":[{"opId":"sec2-c1","scope":"user:${user.userId}","entity":"task","entityId":"$childId","type":"create","fields":{"name":"Child","parentId":"$parentId","listId":"00000000-0000-0000-0000-000000000001"}}]}""")
        }
        val body = json.decodeFromString<SyncResults>(resp.bodyAsText())
        assertEquals("ok", body.results[0].status,
            "SEC-2: Own parent_id should be accepted")
    }

    // ── SEC-4: Access matrix expansion ──

    @Test
    fun `SEC-4 owner can create list`() = testApplication {
        application { moduleForTest() }
        val email = "sec4a-${System.currentTimeMillis()}@test.taskly"
        val reg = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val user = json.decodeFromString<AuthMapper>(reg.bodyAsText())

        val resp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.accessToken)
            setBody("""{"name":"My List"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status, "Owner should be able to create a list")
    }

    @Test
    fun `SEC-4 stranger cannot read anothers list`() = testApplication {
        application { moduleForTest() }

        val emailA = "sec4b-${System.currentTimeMillis()}@test.taskly"
        val regA = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailA","password":"Test123!"}""")
        }
        val userA = json.decodeFromString<AuthMapper>(regA.bodyAsText())

        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(userA.accessToken)
            setBody("""{"name":"Secret"}""")
        }
        val listBody = json.decodeFromString<ListMapper>(listResp.bodyAsText())

        val emailB = "sec4c-${System.currentTimeMillis()}@test.taskly"
        val regB = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailB","password":"Test123!"}""")
        }
        val userB = json.decodeFromString<AuthMapper>(regB.bodyAsText())

        // Stranger tries to read
        val getResp = client.get("/v1/lists/${listBody.id}") {
            bearerAuth(userB.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, getResp.status,
            "SEC-4: Stranger should not see another user's list")
    }

    @Test
    fun `SEC-4 stranger cannot create task in anothers list via REST`() = testApplication {
        application { moduleForTest() }

        val emailA = "sec4d-${System.currentTimeMillis()}@test.taskly"
        val regA = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailA","password":"Test123!"}""")
        }
        val userA = json.decodeFromString<AuthMapper>(regA.bodyAsText())

        val listResp = client.post("/v1/lists") {
            contentType(ContentType.Application.Json)
            bearerAuth(userA.accessToken)
            setBody("""{"name":"Secret"}""")
        }
        val listBody = json.decodeFromString<ListMapper>(listResp.bodyAsText())

        val emailB = "sec4e-${System.currentTimeMillis()}@test.taskly"
        val regB = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailB","password":"Test123!"}""")
        }
        val userB = json.decodeFromString<AuthMapper>(regB.bodyAsText())

        val createResp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            bearerAuth(userB.accessToken)
            setBody("""{"name":"Hacked","listId":"${listBody.id}"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, createResp.status,
            "SEC-4: Stranger cannot create a task in another user's list")
    }

    @Test
    fun `SEC-4 stranger cannot sync into anothers scope`() = testApplication {
        application { moduleForTest() }

        val emailA = "sec4f-${System.currentTimeMillis()}@test.taskly"
        val regA = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailA","password":"Test123!"}""")
        }
        val userA = json.decodeFromString<AuthMapper>(regA.bodyAsText())

        val emailB = "sec4g-${System.currentTimeMillis()}@test.taskly"
        val regB = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$emailB","password":"Test123!"}""")
        }
        val userB = json.decodeFromString<AuthMapper>(regB.bodyAsText())

        // User B tries to sync into User A's scope
        val resp = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(userB.accessToken)
            setBody("""{"ops":[{"opId":"sec4-s1","scope":"user:${userA.userId}","entity":"task","entityId":"00000000-0000-0000-0000-0000000dead1","type":"create","fields":{"name":"Stolen"}}]}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.decodeFromString<SyncResults>(resp.bodyAsText())
        assertEquals("forbidden", body.results[0].status,
            "SEC-4: User B should be forbidden from working in User A's scope")
    }

    // ── SEC-5: Refresh token replay detection ──

    @Test
    fun `SEC-5 refresh token replay revokes whole chain`() = testApplication {
        application { moduleForTest() }

        val email = "sec5r-${System.currentTimeMillis()}@test.taskly"
        val reg = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"Test123!"}""")
        }
        val user = json.decodeFromString<AuthMapper>(reg.bodyAsText())
        val originalRefresh = user.refreshToken ?: throw AssertionError("Should have refresh token")

        // Normal rotation: refresh once
        val refresh1 = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$originalRefresh"}""")
        }
        assertEquals(HttpStatusCode.OK, refresh1.status)
        val rotated = json.decodeFromString<AuthMapper>(refresh1.bodyAsText())

        // Refresh again with original token (replay) — should revoke entire chain
        val replay = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$originalRefresh"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status,
            "SEC-5: Replay of rotated refresh token should be rejected")

        // Even the rotated token should now be revoked
        val rotatedReplay = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"${rotated.refreshToken}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, rotatedReplay.status,
            "SEC-5: After replay detection, the rotated token should also be revoked")
    }

    // DTOs
    @Serializable
    data class AuthMapper(val accessToken: String, val refreshToken: String? = null, val userId: String)
    @Serializable
    data class ListMapper(val id: String, val name: String, val isSmart: Boolean = false, val version: Long, val createdAt: String)
    @Serializable
    data class TaskMapper(val id: String, val name: String, val listId: String, val version: Long, val createdAt: String, val updatedAt: String)
    @Serializable
    data class SyncResultMapper(val opId: String, val status: String, val entityId: String? = null, val version: Long? = null, val error: String? = null)
    @Serializable
    data class SyncResults(val results: List<SyncResultMapper>)
}