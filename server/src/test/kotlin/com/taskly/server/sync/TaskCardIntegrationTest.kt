package com.taskly.server.sync

import com.taskly.server.*
import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task card data (WEB-F3) through the public API: synced notes, link attachments, subtasks,
 * postpone counter and the change history — including the ownership rules for each.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskCardIntegrationTest {

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
    fun `notes are created, edited and deleted through sync and travel in the delta and snapshot`() = testApplication {
        application { moduleForTest() }
        val u = client.register("notes")
        val task = client.newTask(u, "With notes")
        val note = uuid()

        assertEquals("ok", client.push(u, op(u, "note", note, "create", """{"taskId":"$task","content":"**bold** first"}""")).status())
        assertEquals("ok", client.push(u, op(u, "note", note, "update", """{"content":"edited"}""")).status())

        val changes = client.changes(u).filter { it.str("entity") == "note" && it.str("entityId") == note }
        assertEquals(listOf("create", "update"), changes.map { it.str("op") })
        val payload = json.parseToJsonElement(changes.last().str("payload")).jsonObject
        assertEquals("edited", payload.str("content"))
        assertEquals(task, payload.str("task_id"))

        val snapNotes = client.snapshot(u)["notes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("edited"), snapNotes.filter { it.str("taskId") == task }.map { it.str("content") })

        assertEquals("ok", client.push(u, op(u, "note", note, "delete")).status())
        assertTrue(client.snapshot(u)["notes"]!!.jsonArray.none { it.jsonObject.str("id") == note }, "deleted notes leave the snapshot")
        assertTrue(client.changes(u).any { it.str("entityId") == note && it.str("op") == "delete" })
    }

    @Test
    fun `notes and attachments cannot be attached to or changed on another user's task`() = testApplication {
        application { moduleForTest() }
        val owner = client.register("owner")
        val intruder = client.register("intruder")
        val task = client.newTask(owner, "Private")
        val note = uuid()
        assertEquals("ok", client.push(owner, op(owner, "note", note, "create", """{"taskId":"$task","content":"secret"}""")).status())

        val attachForeign = client.push(intruder, op(intruder, "note", uuid(), "create", """{"taskId":"$task","content":"spam"}""")).status()
        assertEquals("not_found", attachForeign)
        val editForeign = client.push(intruder, op(intruder, "note", note, "update", """{"content":"hacked"}""")).status()
        assertEquals("not_found", editForeign)
        val linkForeign = client.push(intruder, op(intruder, "attachment", uuid(), "create", """{"taskId":"$task","url":"https://example.com/x"}""")).status()
        assertEquals("not_found", linkForeign)

        val notes = client.snapshot(owner)["notes"]!!.jsonArray.map { it.jsonObject.str("content") }
        assertEquals(listOf("secret"), notes)
    }

    @Test
    fun `attachments accept only http links`() = testApplication {
        application { moduleForTest() }
        val u = client.register("links")
        val task = client.newTask(u, "With files")
        assertEquals("rejected", client.push(u, op(u, "attachment", uuid(), "create", """{"taskId":"$task","url":"javascript:alert(1)"}""")).status())
        assertEquals("rejected", client.push(u, op(u, "attachment", uuid(), "create", """{"taskId":"$task","url":"file:///etc/passwd"}""")).status())

        val link = uuid()
        assertEquals("ok", client.push(u, op(u, "attachment", link, "create", """{"taskId":"$task","name":"Spec","url":"https://drive.example.com/spec"}""")).status())
        val atts = client.snapshot(u)["attachments"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Spec"), atts.map { it.str("name") })

        assertEquals("ok", client.push(u, op(u, "attachment", link, "delete")).status())
        assertTrue(client.snapshot(u)["attachments"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `subtasks are limited to three levels, no cycles, own tasks only`() = testApplication {
        application { moduleForTest() }
        val u = client.register("tree")
        val other = client.register("tree-other")
        val l1 = client.newTask(u, "Level 1")
        val l2 = uuid()
        val l3 = uuid()
        assertEquals("ok", client.push(u, op(u, "task", l2, "create", """{"name":"Level 2","parentId":"$l1"}""")).status())
        assertEquals("ok", client.push(u, op(u, "task", l3, "create", """{"name":"Level 3","parentId":"$l2"}""")).status())
        assertEquals("rejected", client.push(u, op(u, "task", uuid(), "create", """{"name":"Level 4","parentId":"$l3"}""")).status())
        assertEquals("rejected", client.push(u, op(u, "task", l1, "update", """{"parentId":"$l3"}""")).status(), "cycle")
        assertEquals("rejected", client.push(u, op(u, "task", l1, "update", """{"parentId":"$l1"}""")).status(), "self")

        val foreign = client.newTask(other, "Not yours")
        assertEquals("forbidden", client.push(u, op(u, "task", uuid(), "create", """{"name":"x","parentId":"$foreign"}""")).status())

        val sub = client.get("/v1/tasks/$l2") { bearerAuth(u.token) }.obj()
        assertEquals(l1, sub.str("parentId"))
    }

    @Test
    fun `postpone counter is synced and history lists the task card changes`() = testApplication {
        application { moduleForTest() }
        val u = client.register("postpone")
        val task = client.newTask(u, "Later")
        assertEquals("ok", client.push(u, op(u, "task", task, "update", """{"dueAt":"2026-10-20T00:00:00Z","postponedCount":1}""")).status())
        val got = client.get("/v1/tasks/$task") { bearerAuth(u.token) }.obj()
        assertEquals(1, got["postponedCount"]!!.jsonPrimitive.int)
        assertEquals("rejected", client.push(u, op(u, "task", task, "update", """{"postponedCount":-1}""")).status())

        val note = uuid()
        client.push(u, op(u, "note", note, "create", """{"taskId":"$task","content":"why"}"""))
        val history = client.get("/v1/tasks/$task/history") { bearerAuth(u.token) }
        assertEquals(HttpStatusCode.OK, history.status)
        val entries = json.parseToJsonElement(history.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals("note", entries.first().str("entity"), "newest first")
        assertTrue(entries.any { it.str("entity") == "task" && it.str("op") == "create" })
        assertTrue(entries.any { it.str("entity") == "task" && it.str("op") == "update" })

        val stranger = client.register("stranger")
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/tasks/$task/history") { bearerAuth(stranger.token) }.status)
    }

    @Test
    fun `next instance of a repeating task gets tags and notes through the delta`() = testApplication {
        application { moduleForTest() }
        val u = client.register("repeat")
        val task = uuid()
        client.push(u, op(u, "task", task, "create", """{"name":"Weekly","dueAt":"2026-10-19T09:00:00Z","dueHasTime":true,"rrule":"FREQ=WEEKLY","repeatMode":"every"}"""))
        client.push(u, op(u, "task", task, "tag_add", """{"tag":"team"}"""))
        client.push(u, op(u, "note", uuid(), "create", """{"taskId":"$task","content":"agenda"}"""))
        assertEquals("ok", client.push(u, op(u, "task", task, "complete")).status())

        val changes = client.changes(u)
        val next = changes.last { it.str("entity") == "task" && it.str("op") == "create" }.str("entityId")
        assertTrue(next != task)
        assertTrue(changes.any { it.str("entityId") == next && it.str("op") == "tag_add" }, "tag of the next instance is in the delta")
        val copiedNote = changes.firstOrNull { it.str("entity") == "note" && json.parseToJsonElement(it.str("payload")).jsonObject.str("task_id") == next }
        assertEquals("agenda", copiedNote?.let { json.parseToJsonElement(it.str("payload")).jsonObject.str("content") })
    }

    @Test
    fun `a deleted task can be restored (undo) and only by its owner`() = testApplication {
        application { moduleForTest() }
        val u = client.register("restore")
        val other = client.register("restore-other")
        val task = client.newTask(u, "Oops")
        assertEquals("ok", client.push(u, op(u, "task", task, "delete")).status())
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/tasks/$task") { bearerAuth(u.token) }.status)

        assertEquals("not_found", client.push(other, op(other, "task", task, "restore")).status())
        assertEquals("ok", client.push(u, op(u, "task", task, "restore")).status())
        assertEquals(HttpStatusCode.OK, client.get("/v1/tasks/$task") { bearerAuth(u.token) }.status)
        val restore = client.changes(u).last { it.str("entityId") == task }
        assertEquals("restore", restore.str("op"))
        assertEquals("Oops", json.parseToJsonElement(restore.str("payload")).jsonObject.str("name"))
    }

    // ── Helpers ──

    private data class User(val token: String, val id: String)

    private fun uuid() = UUID.randomUUID().toString()

    private fun op(u: User, entity: String, id: String, type: String, fields: String = "{}") =
        """{"opId":"${uuid()}","scope":"user:${u.id}","entity":"$entity","entityId":"$id","type":"$type","fields":$fields}"""

    private suspend fun HttpClient.register(prefix: String): User {
        val r = postJson("/v1/auth/register", """{"email":"$prefix-${System.nanoTime()}@test.taskly","password":"Password123"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val o = r.obj()
        return User(o.str("accessToken"), o.str("userId"))
    }

    private suspend fun HttpClient.newTask(u: User, name: String): String {
        val id = uuid()
        assertEquals("ok", push(u, op(u, "task", id, "create", """{"name":"$name"}""")).status())
        return id
    }

    private suspend fun HttpClient.push(u: User, opJson: String): JsonObject {
        val r = postJson("/v1/sync", """{"ops":[$opJson]}""", u.token)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return r.obj()["results"]!!.jsonArray.single().jsonObject
    }

    private fun JsonObject.status() = str("status")

    private suspend fun HttpClient.changes(u: User): List<JsonObject> {
        val r = get("/v1/sync/changes?scope=user:${u.id}&limit=1000") { bearerAuth(u.token) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return (r.obj()["changes"] as JsonArray).map { it.jsonObject }
    }

    private suspend fun HttpClient.snapshot(u: User): JsonObject {
        val r = get("/v1/sync/snapshot") { bearerAuth(u.token) }
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
