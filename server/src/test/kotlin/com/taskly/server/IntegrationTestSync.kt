package com.taskly.server

import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.*

/**
 * Integration tests for the security and correctness fixes:
 * sync ownership/scopes, per-op isolation, shared query language in the API,
 * shared recurrence engine on complete, and auth status codes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTestSync {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val moscow = ZoneId.of("Europe/Moscow")

    @BeforeAll
    fun setup() {
        check(Config.database.database.endsWith("_test")) {
            "Integration tests must use a *_test database (PGDATABASE), got '${Config.database.database}'"
        }
        Database.init(Config.database)
        RedisService.init(Config.redis)
        RateLimiter.init(Config.rateLimit)
        AuthService.init(Config.auth)
    }

    @BeforeEach
    fun cleanup() {
        Database.ctx().execute(
            "TRUNCATE TABLE tasks, lists, tags, task_tags, notes, reminders, refresh_tokens, users, processed_ops, change_log RESTART IDENTITY CASCADE"
        )
    }

    @AfterAll
    fun teardown() {
        RedisService.close()
        Database.shutdown()
    }

    // ---------------------------------------------------------------- helpers

    private data class User(val token: String, val id: String)

    private suspend fun HttpClient.register(prefix: String): User {
        val resp = post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$prefix-${UUID.randomUUID()}@taskly.test","password":"Test123!","tz":"Europe/Moscow"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        val auth = json.decodeFromString<AuthResponseMapper>(resp.bodyAsText())
        return User(auth.accessToken, auth.userId)
    }

    private suspend fun HttpClient.createList(u: User, name: String): String {
        val resp = post("/v1/lists") {
            contentType(ContentType.Application.Json); bearerAuth(u.token)
            setBody("""{"name":"$name"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return json.decodeFromString<ListResponseMapper>(resp.bodyAsText()).id
    }

    private suspend fun HttpClient.createTask(u: User, body: String): TaskResponseMapper {
        val resp = post("/v1/tasks") {
            contentType(ContentType.Application.Json); bearerAuth(u.token)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return json.decodeFromString(resp.bodyAsText())
    }

    private suspend fun HttpClient.sync(u: User, opsJson: String): SyncResponseMapper {
        val resp = post("/v1/sync") {
            contentType(ContentType.Application.Json); bearerAuth(u.token)
            setBody("""{"ops":[$opsJson]}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return json.decodeFromString(resp.bodyAsText())
    }

    private suspend fun HttpClient.tasks(u: User, q: String? = null): List<TaskResponseMapper> {
        val resp = get("/v1/tasks") {
            bearerAuth(u.token)
            if (q != null) parameter("q", q)
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return json.decodeFromString<PaginatedResponseMapper>(resp.bodyAsText()).data
    }

    private suspend fun HttpClient.complete(u: User, id: String): CompleteResponseMapper {
        val resp = post("/v1/tasks/$id/complete") { bearerAuth(u.token) }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return json.decodeFromString(resp.bodyAsText())
    }

    private fun op(u: User, opId: String, entityId: String, type: String, fields: String = "{}", entity: String = "task", scope: String = "user:${u.id}") =
        """{"opId":"$opId","scope":"$scope","entity":"$entity","entityId":"$entityId","type":"$type","fields":$fields}"""

    // ---------------------------------------------------------------- auth

    @Test
    fun `missing or bad token returns 401`() = testApplication {
        application { moduleForTest() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/tasks").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/tasks") { bearerAuth("garbage") }.status)
    }

    // ---------------------------------------------------------------- sync security

    @Test
    fun `sync create cannot take over another user's task`() = testApplication {
        application { moduleForTest() }
        val alice = client.register("alice")
        val mallory = client.register("mallory")
        val task = client.createTask(alice, """{"name":"Alice private"}""")

        val res = client.sync(mallory, op(mallory, "steal-1", task.id, "create", """{"name":"pwned"}"""))
        assertEquals("forbidden", res.results.single().status)

        val aliceTasks = client.tasks(alice)
        assertEquals("Alice private", aliceTasks.single { it.id == task.id }.name)
        assertTrue(client.tasks(mallory).none { it.id == task.id })
    }

    @Test
    fun `sync create cannot rename another user's list`() = testApplication {
        application { moduleForTest() }
        val alice = client.register("alice")
        val mallory = client.register("mallory")
        val listId = client.createList(alice, "Secret")

        val res = client.sync(mallory, op(mallory, "list-1", listId, "create", """{"name":"pwned"}""", entity = "list"))
        assertEquals("forbidden", res.results.single().status)
        val lists = client.get("/v1/lists") { bearerAuth(alice.token) }.bodyAsText()
        assertTrue(lists.contains("Secret") && !lists.contains("pwned"), lists)
    }

    @Test
    fun `task cannot be placed into another user's list`() = testApplication {
        application { moduleForTest() }
        val alice = client.register("alice")
        val mallory = client.register("mallory")
        val aliceList = client.createList(alice, "Alice")

        val resp = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json); bearerAuth(mallory.token)
            setBody("""{"name":"x","listId":"$aliceList"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `foreign scope is rejected for ops and for changes`() = testApplication {
        application { moduleForTest() }
        val alice = client.register("alice")
        val mallory = client.register("mallory")
        client.createTask(alice, """{"name":"Alice task"}""")

        val changes = client.get("/v1/sync/changes") {
            bearerAuth(mallory.token); parameter("scope", "user:${alice.id}")
        }
        assertEquals(HttpStatusCode.Forbidden, changes.status)

        val res = client.sync(
            mallory,
            op(mallory, "scope-1", UUID.randomUUID().toString(), "create", """{"name":"x"}""", scope = "user:${alice.id}")
        )
        assertEquals("forbidden", res.results.single().status)

        val own = client.get("/v1/sync/changes") { bearerAuth(alice.token); parameter("scope", "user:${alice.id}") }
        assertEquals(HttpStatusCode.OK, own.status)
        assertTrue(own.bodyAsText().contains("Alice task"), "REST create must be written to change_log")
    }

    // ---------------------------------------------------------------- sync correctness

    @Test
    fun `one failing op does not abort the batch`() = testApplication {
        application { moduleForTest() }
        val u = client.register("batch")
        val a = UUID.randomUUID().toString()
        val c = UUID.randomUUID().toString()
        val res = client.sync(
            u,
            listOf(
                op(u, "b-1", a, "create", """{"name":"first"}"""),
                op(u, "b-2", UUID.randomUUID().toString(), "create", """{"name":"bad","priority":7}"""),
                op(u, "b-3", c, "create", """{"name":"third"}"""),
            ).joinToString(",")
        )
        assertEquals(listOf("ok", "error", "ok"), res.results.map { it.status })
        val names = client.tasks(u).map { it.name }.toSet()
        assertEquals(setOf("first", "third"), names)

        // Replay of the failed op returns the same answer without side effects
        val replay = client.sync(u, op(u, "b-1", a, "create", """{"name":"first"}"""))
        assertEquals("ok", replay.results.single().status)
        assertEquals(2, client.tasks(u).size)
    }

    @Test
    fun `op ids are scoped per user`() = testApplication {
        application { moduleForTest() }
        val a = client.register("a")
        val b = client.register("b")
        val ra = client.sync(a, op(a, "same-op", UUID.randomUUID().toString(), "create", """{"name":"A"}"""))
        val rb = client.sync(b, op(b, "same-op", UUID.randomUUID().toString(), "create", """{"name":"B"}"""))
        assertEquals("ok", ra.results.single().status)
        assertEquals("ok", rb.results.single().status)
        assertEquals(listOf("B"), client.tasks(b).map { it.name })
    }

    @Test
    fun `json null clears a field and update bumps version`() = testApplication {
        application { moduleForTest() }
        val u = client.register("nulls")
        val t = client.createTask(u, """{"name":"with due","dueAt":"2026-11-01T10:00:00+03:00","url":"https://x"}""")
        val res = client.sync(u, op(u, "n-1", t.id, "update", """{"dueAt":null,"name":"renamed"}"""))
        assertEquals("ok", res.results.single().status)
        val after = client.tasks(u).single()
        assertNull(after.dueAt)
        assertEquals("renamed", after.name)
        assertEquals("https://x", after.url)
        assertTrue(after.version > t.version)
    }

    @Test
    fun `stale field update loses to newer server value (LWW)`() = testApplication {
        application { moduleForTest() }
        val u = client.register("lww")
        val id = UUID.randomUUID().toString()
        client.sync(u, op(u, "l-1", id, "create", """{"name":"v1"}"""))
        // Device A renames with base version 1
        client.sync(u, """{"opId":"l-2","scope":"user:${u.id}","entity":"task","entityId":"$id","type":"update","fields":{"name":"from A"},"baseVersion":1}""")
        // Device B, still on base version 1, renames and changes priority
        val res = client.sync(u, """{"opId":"l-3","scope":"user:${u.id}","entity":"task","entityId":"$id","type":"update","fields":{"name":"from B","priority":2},"baseVersion":1}""")
        assertEquals("ok", res.results.single().status)
        val t = client.tasks(u).single()
        assertEquals("from A", t.name, "stale rename must be skipped")
        assertEquals(2, t.priority, "untouched field from the same op is applied")
    }

    @Test
    fun `sync complete creates next instance once`() = testApplication {
        application { moduleForTest() }
        val u = client.register("synccomplete")
        val t = client.createTask(u, """{"name":"Weekly","dueAt":"2026-10-19T10:00:00+03:00","rrule":"FREQ=WEEKLY","repeatMode":"every"}""")
        client.sync(u, op(u, "c-1", t.id, "complete"))
        client.sync(u, op(u, "c-2", t.id, "complete")) // duplicate from another device
        val open = client.tasks(u, "completed:false")
        assertEquals(1, open.size, "exactly one next instance: $open")
        assertEquals(OffsetDateTime.parse("2026-10-26T10:00:00+03:00").toInstant(), OffsetDateTime.parse(open.single().dueAt).toInstant())
    }

    // ---------------------------------------------------------------- recurrence (shared engine)

    @Test
    fun `every mode keeps schedule and local time, copies tags, no duplicate on second complete`() = testApplication {
        application { moduleForTest() }
        val u = client.register("every")
        client.post("/v1/tags") { contentType(ContentType.Application.Json); bearerAuth(u.token); setBody("""{"name":"team"}""") }
        val t = client.createTask(u, """{"name":"Standup","dueAt":"2026-10-19T09:30:00+03:00","dueHasTime":true,"rrule":"FREQ=WEEKLY;BYDAY=MO,TH","repeatMode":"every","tags":["team"]}""")

        val first = client.complete(u, t.id)
        val next = assertNotNull(first.nextInstance)
        // Monday 19th → Thursday 22nd (BYDAY honoured), same local time
        assertEquals(OffsetDateTime.parse("2026-10-22T09:30:00+03:00").toInstant(), OffsetDateTime.parse(next.dueAt).toInstant())
        assertEquals(1, next.tags?.size, "tags are copied to the next instance")

        val second = client.complete(u, t.id)
        assertNull(second.nextInstance, "completing again must not create another instance")
        assertEquals(1, client.tasks(u, "completed:false").size)
    }

    @Test
    fun `after mode shifts from completion date`() = testApplication {
        application { moduleForTest() }
        val u = client.register("after")
        // Planned long ago; "after 2 days" counts from today's completion
        val t = client.createTask(u, """{"name":"Water plants","dueAt":"2026-01-01T12:00:00+03:00","rrule":"FREQ=DAILY;INTERVAL=2","repeatMode":"after"}""")
        val next = assertNotNull(client.complete(u, t.id).nextInstance)
        val expected = LocalDate.now(moscow).plusDays(2)
        assertEquals(expected, OffsetDateTime.parse(next.dueAt).atZoneSameInstant(moscow).toLocalDate())
    }

    @Test
    fun `series ends at UNTIL`() = testApplication {
        application { moduleForTest() }
        val u = client.register("until")
        val t = client.createTask(u, """{"name":"Last","dueAt":"2026-10-19T10:00:00+03:00","rrule":"FREQ=WEEKLY;UNTIL=20261020","repeatMode":"every"}""")
        assertNull(client.complete(u, t.id).nextInstance)
    }

    // ---------------------------------------------------------------- smart list query (shared engine)

    @Test
    fun `API evaluates the shared query language (AC-6)`() = testApplication {
        application { moduleForTest() }
        val u = client.register("query")
        val work = client.createList(u, "Работа")
        val home = client.createList(u, "Дом")
        val soon = OffsetDateTime.now(moscow).plusDays(1).withHour(12).toString()
        val later = OffsetDateTime.now(moscow).plusDays(10).withHour(12).toString()

        client.createTask(u, """{"name":"w-p1-later","listId":"$work","priority":1,"dueAt":"$later"}""")
        client.createTask(u, """{"name":"w-p3-soon","listId":"$work","priority":3,"dueAt":"$soon"}""")
        client.createTask(u, """{"name":"w-p3-later","listId":"$work","priority":3,"dueAt":"$later"}""")
        client.createTask(u, """{"name":"h-p1-soon","listId":"$home","priority":1,"dueAt":"$soon"}""")

        val found = client.tasks(u, """list:Работа AND (priority:1 OR dueBefore:"3 days")""").map { it.name }.toSet()
        assertEquals(setOf("w-p1-later", "w-p3-soon"), found)

        val notHome = client.tasks(u, "NOT list:Дом AND priority:3").map { it.name }.toSet()
        assertEquals(setOf("w-p3-soon", "w-p3-later"), notHome)

        // Fields that touch auxiliary tables must not crash
        assertEquals(HttpStatusCode.OK, client.get("/v1/tasks") { bearerAuth(u.token); parameter("q", "location:office OR isShared:true OR postponed:>2") }.status)
        // Malformed query → 400, not 500
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/tasks") { bearerAuth(u.token); parameter("q", "priority:1 AND (") }.status)
    }
}

@Serializable
data class SyncResultMapper(val opId: String, val status: String, val entityId: String? = null, val version: Long? = null, val error: String? = null)

@Serializable
data class SyncResponseMapper(val results: List<SyncResultMapper>, val cursor: String? = null)

@Serializable
data class CompleteResponseMapper(val task: TaskResponseMapper, val nextInstance: TaskResponseMapper? = null)
