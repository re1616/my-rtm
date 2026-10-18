package com.taskly.server.sync

import com.taskly.server.*
import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Initial snapshot (WEB-S4/AND-S6) and the realtime WebSocket channel (WEB-S5/AND-S4). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotAndRealtimeIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setup() {
        check(Config.database.database.endsWith("_test")) { "Integration tests must use a *_test database (PGDATABASE)" }
        Database.init(Config.database)
        RedisService.init(Config.redis)
        RateLimiter.init(Config.rateLimit)
        AuthService.init(Config.auth)
    }

    @AfterAll
    fun teardown() {
        RedisService.close()
        Database.shutdown()
    }

    @Test
    fun `snapshot pages through tasks and returns lists, tags and the change-log position`() = testApplication {
        application { moduleForTest() }
        val token = client.register("snap")
        val list = client.postJson("/v1/lists", """{"name":"Work"}""", token).obj().str("id")
        client.postJson("/v1/tags", """{"name":"urgent"}""", token)
        val ids = (1..5).map { client.postJson("/v1/tasks", """{"name":"Task $it","listId":"$list"}""", token).obj().str("id") }
        val deleted = ids.first()
        assertEquals(HttpStatusCode.NoContent.value / 100, client.delete("/v1/tasks/$deleted") { bearerAuth(token) }.status.value / 100)

        val first = client.snapshot(token, null, 3)
        assertTrue(first["seq"]!!.jsonPrimitive.long > 0, "first page carries the change-log position")
        assertEquals(3, first["tasks"]!!.jsonArray.size)
        assertTrue(first["lists"]!!.jsonArray.any { it.jsonObject.str("name") == "Work" })
        assertTrue(first["tags"]!!.jsonArray.any { it.jsonObject.str("name") == "urgent" })
        val cursor = first.str("nextCursor")

        val second = client.snapshot(token, cursor, 3)
        assertTrue(second["seq"] == null || second["seq"] is JsonNull, "seq only on the first page")
        assertTrue(second["lists"]!!.jsonArray.isEmpty())
        assertTrue(second["nextCursor"] == null || second["nextCursor"] is JsonNull, "last page")

        val all = (first["tasks"]!!.jsonArray + second["tasks"]!!.jsonArray).map { it.jsonObject.str("id") }
        assertEquals(ids.drop(1).toSet(), all.toSet(), "every live task exactly once, deleted ones excluded")
        assertEquals(all.size, all.toSet().size)

        // Nothing is lost: changes made after the snapshot start are returned by the delta after seq
        val later = client.postJson("/v1/tasks", """{"name":"after snapshot"}""", token).obj().str("id")
        val seq = first["seq"]!!.jsonPrimitive.long
        val me = client.get("/v1/auth/account") { bearerAuth(token) }.obj().str("userId")
        val delta = client.get("/v1/sync/changes?scope=user:$me&after=$seq") { bearerAuth(token) }.obj()
        assertTrue(delta["changes"]!!.jsonArray.any { it.jsonObject.str("entityId") == later })
    }

    @Test
    fun `snapshot rejects a malformed cursor and needs auth`() = testApplication {
        application { moduleForTest() }
        val token = client.register("snapbad")
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/sync/snapshot?cursor=nope") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync/snapshot").status)
    }

    @Test
    fun `websocket pushes a sync_update after a change by the same user`() = testApplication {
        application { moduleForTest() }
        val token = client.register("ws")
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/v1/ws") {
            send(Frame.Text("""{"type":"auth","payload":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive("""{"token":"$token"}"""))}}"""))
            val ok = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("auth_ok", ok.str("type"))

            // The Redis subscription starts asynchronously; retry the change a few times
            var update: JsonObject? = null
            for (attempt in 1..5) {
                val id = client.postJson("/v1/tasks", """{"name":"realtime $attempt"}""", token).obj().str("id")
                update = try {
                    withTimeout(2_000) {
                        var found: JsonObject? = null
                        while (found == null) {
                            val msg = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                            if (msg.str("type") == "sync_update") {
                                val payload = json.parseToJsonElement(msg.str("payload")).jsonObject
                                if (payload.str("entityId") == id) found = payload
                            }
                        }
                        found
                    }
                } catch (_: TimeoutCancellationException) {
                    null
                }
                if (update != null) break
            }
            val received = update ?: throw AssertionError("no sync_update received over the WebSocket")
            assertEquals("task", received.str("entity"))
            assertTrue(received["seq"]!!.jsonPrimitive.long > 0)
        }
    }

    @Test
    fun `websocket rejects a bad token and does not deliver other users changes`() = testApplication {
        application { moduleForTest() }
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/v1/ws") {
            send(Frame.Text("""{"type":"auth","payload":"{\"token\":\"forged\"}"}"""))
            val msg = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            assertEquals("error", msg.str("type"))
        }

        val mine = client.register("wsmine")
        val other = client.register("wsother")
        ws.webSocket("/v1/ws") {
            send(Frame.Text("""{"type":"auth","payload":"{\"token\":\"$mine\"}"}"""))
            assertEquals("auth_ok", json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject.str("type"))
            kotlinx.coroutines.delay(500)
            client.postJson("/v1/tasks", """{"name":"someone else's"}""", other)
            val leaked = try {
                withTimeout(1_500) { (incoming.receive() as Frame.Text).readText() }
            } catch (_: TimeoutCancellationException) {
                null
            }
            assertNull(leaked, "changes of another user must not reach this socket")
        }
    }

    // ── Helpers ──

    private suspend fun HttpClient.register(prefix: String): String {
        val r = postJson("/v1/auth/register", """{"email":"$prefix-${System.nanoTime()}@test.taskly","password":"Password123"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.obj().str("accessToken")
    }

    private suspend fun HttpClient.snapshot(token: String, cursor: String?, limit: Int): JsonObject {
        val r = get("/v1/sync/snapshot?limit=$limit${cursor?.let { "&cursor=$it" } ?: ""}") {
            bearerAuth(token)
            header("Taskly-Sync-Version", "2")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return r.obj()
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            token?.let { bearerAuth(it) }
            setBody(body)
        }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
