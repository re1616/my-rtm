package com.taskly.server.features

import com.taskly.server.*
import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.db.FeaturesTable
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.*

/**
 * Integration tests for SEC-7: /v1/features endpoint.
 * Tests: basic request, plan filtering, rollout percentage, cache behavior.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeaturesIntegrationTest {

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
        ctx.execute("TRUNCATE TABLE tasks, lists, tags, task_tags, notes, reminders, refresh_tokens, users, processed_ops, change_log, feature_flags RESTART IDENTITY CASCADE")
        seedFlags()
    }

    @AfterAll
    fun teardown() {
        RedisService.close()
        Database.shutdown()
    }

    private fun seedFlags() {
        val ctx = Database.ctx()
        ctx.insertInto(FeaturesTable.TABLE)
            .set(FeaturesTable.KEY, "flag_all")
            .set(FeaturesTable.ROLLOUT_PCT, 100)
            .set(FeaturesTable.ENABLED, true)
            .execute()
        ctx.insertInto(FeaturesTable.TABLE)
            .set(FeaturesTable.KEY, "flag_pro_only")
            .set(FeaturesTable.ROLLOUT_PCT, 100)
            .set(FeaturesTable.PLAN_FILTER, "pro,business")
            .set(FeaturesTable.ENABLED, true)
            .execute()
        ctx.insertInto(FeaturesTable.TABLE)
            .set(FeaturesTable.KEY, "flag_rollout_50")
            .set(FeaturesTable.ROLLOUT_PCT, 50)
            .set(FeaturesTable.ENABLED, true)
            .execute()
        ctx.insertInto(FeaturesTable.TABLE)
            .set(FeaturesTable.KEY, "flag_disabled")
            .set(FeaturesTable.ROLLOUT_PCT, 100)
            .set(FeaturesTable.ENABLED, false)
            .execute()
        ctx.insertInto(FeaturesTable.TABLE)
            .set(FeaturesTable.KEY, "flag_kill_switched")
            .set(FeaturesTable.ROLLOUT_PCT, 100)
            .set(FeaturesTable.ENABLED, true)
            .set(FeaturesTable.KILL_SWITCH, true)
            .execute()
        ctx.insertInto(FeaturesTable.TABLE)
            .set(FeaturesTable.KEY, "flag_version_range")
            .set(FeaturesTable.ROLLOUT_PCT, 100)
            .set(FeaturesTable.VERSION_MIN, "1.0.0")
            .set(FeaturesTable.VERSION_MAX, "2.0.0")
            .set(FeaturesTable.ENABLED, true)
            .execute()
    }

    @Test
    fun `features endpoint returns 200 with flags map`() = testApplication {
        application { moduleForTest() }

        val email = "test-feat-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegRequest(email, "Test123!")))
        }
        assertEquals(HttpStatusCode.Created, registerResp.status)
        val auth = json.decodeFromString<RegResponse>(registerResp.bodyAsText())
        val token = auth.accessToken

        val resp = client.get("/v1/features") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("flag_all"), "Should contain universal flag: $body")
        assertFalse(body.contains("flag_pro_only"), "Free plan should not see pro_only: $body")
    }

    @Test
    fun `features endpoint respects plan filter`() = testApplication {
        application { moduleForTest() }

        val email = "test-feat-plan-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegRequest(email, "Test123!")))
        }
        val auth = json.decodeFromString<RegResponse>(registerResp.bodyAsText())

        val resp = client.get("/v1/features?plan=pro") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("flag_pro_only"), "Pro plan should see pro_only: $body")
        assertTrue(body.contains("flag_all"), "Pro plan should see universal: $body")
    }

    @Test
    fun `features endpoint respects enabled flag`() = testApplication {
        application { moduleForTest() }

        val email = "test-feat-en-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegRequest(email, "Test123!")))
        }
        val auth = json.decodeFromString<RegResponse>(registerResp.bodyAsText())

        val resp = client.get("/v1/features") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertFalse(resp.bodyAsText().contains("flag_disabled"), "Disabled flag should not appear")
    }

    @Test
    fun `features endpoint respects kill switch`() = testApplication {
        application { moduleForTest() }

        val email = "test-feat-ks-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegRequest(email, "Test123!")))
        }
        val auth = json.decodeFromString<RegResponse>(registerResp.bodyAsText())

        val resp = client.get("/v1/features") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertFalse(resp.bodyAsText().contains("flag_kill_switched"), "Kill-switched flag should not appear")
    }

    @Test
    fun `features endpoint respects version range`() = testApplication {
        application { moduleForTest() }

        val email = "test-feat-vr-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegRequest(email, "Test123!")))
        }
        val auth = json.decodeFromString<RegResponse>(registerResp.bodyAsText())

        // Version outside range
        val resp1 = client.get("/v1/features?version=0.9.0") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, resp1.status)
        assertFalse(resp1.bodyAsText().contains("flag_version_range"), "Version 0.9.0 should not see range flag")

        // Version inside range
        val resp2 = client.get("/v1/features?version=1.5.0") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, resp2.status)
        assertTrue(resp2.bodyAsText().contains("flag_version_range"), "Version 1.5.0 should see range flag")
    }

    @Test
    fun `features endpoint requires auth`() = testApplication {
        application { moduleForTest() }
        val resp = client.get("/v1/features")
        // Auth middleware returns 401 for unauthenticated requests
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `features endpoint works without caching`() = testApplication {
        application { moduleForTest() }

        val email = "test-feat-nocache-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegRequest(email, "Test123!")))
        }
        val auth = json.decodeFromString<RegResponse>(registerResp.bodyAsText())

        // First call
        val resp1 = client.get("/v1/features") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, resp1.status)
        assertTrue(resp1.bodyAsText().contains("flag_all"), "First call should return flags")

        // Without Redis caching, removing flags from DB affects subsequent calls
        Database.ctx().deleteFrom(FeaturesTable.TABLE).execute()

        val resp2 = client.get("/v1/features") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, resp2.status)
        // Without cache, removed flags won't appear
        assertFalse(resp2.bodyAsText().contains("flag_all"), "Without cache, removed flags should not appear")
    }

    @Test
    fun `free user gets free features`() = testApplication {
        application { moduleForTest() }

        val email = "test-free-${System.currentTimeMillis()}@taskly.test"
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegRequest(email, "Test123!")))
        }
        val auth = json.decodeFromString<RegResponse>(registerResp.bodyAsText())

        val resp = client.get("/v1/features") {
            bearerAuth(auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("flag_all"), "Free: should see flag_all")
        assertFalse(body.contains("flag_pro_only"), "Free: should NOT see flag_pro_only")
    }
}

// Mapper DTOs
@Serializable
data class RegRequest(val email: String, val password: String)
@Serializable
data class RegResponse(val accessToken: String, val refreshToken: String, val userId: String)