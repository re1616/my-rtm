package com.taskly.android.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.TasklyApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4 sync behaviour (AND-S4…S7, SYNC-6/7) with the production [TasklyApiClient] over a scripted
 * HTTP server (Ktor MockEngine): snapshot paging with progress, every entity in the delta,
 * protection of unsent edits, conflicts, token refresh, full-text search, smart lists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineM4Test {
    private lateinit var db: TasklyDatabase
    private lateinit var engine: SyncEngine
    private lateinit var repo: TaskRepository
    private val user = "6f1c2b9e-0000-4000-8000-000000000001"
    private val server = FakeServer()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).allowMainThreadQueries().build()
        val http = HttpClient(MockEngine { server.handle(this, it) }) {
            expectSuccess = true
            install(ContentNegotiation) { json(TasklyApiClient.json) }
            defaultRequest {
                url("http://taskly.test")
                contentType(ContentType.Application.Json)
                header("Taskly-Sync-Version", TasklyApiClient.SYNC_PROTOCOL_VERSION)
            }
        }
        engine = SyncEngine(db, TasklyApiClient(http))
        repo = TaskRepository(db, engine)
    }

    @After
    fun tearDown() = db.close()

    private val tokens = object : TokenProvider {
        var token = "old"
        var refreshes = 0
        override fun accessToken() = token
        override suspend fun refresh(): String? { refreshes++; token = "fresh"; return token }
    }

    @Test
    fun firstSync_loadsSnapshotPagesWithProgress_thenContinuesFromItsPosition() = runBlocking<Unit> {
        server.snapshotPages = listOf(
            snapshotPage(seq = 40, tasks = listOf(task("a1", "Купить молоко"), task("a2", "Отчёт")), next = "a2",
                extra = """"lists":[{"id":"L1","name":"Работа","createdAt":"2026-10-01T00:00:00Z"}],
                           "tags":[{"id":"T1","name":"срочно"}],
                           "locations":[{"id":"P1","name":"Офис"}],
                           "notes":[{"id":"n1","taskId":"a2","content":"квартальный бюджет","createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"}],"""),
            snapshotPage(seq = null, tasks = listOf(task("a3", "Позвонить")), next = null),
        )
        server.changes = listOf(change(41, "task", "a3", "update", """{"name":"Позвонить маме"}"""))
        val progress = mutableListOf<Int>()

        val cursor = engine.syncNow(user, tokens, 0) { progress += -1 }
        assertEquals(41, cursor)
        assertEquals(3, db.taskDao().count(user))
        assertEquals("Работа", db.listDao().getById("L1")?.name)
        assertEquals("Позвонить маме", db.taskDao().getById("a3")?.name, "delta after the snapshot position")
        assertEquals(listOf("срочно"), db.tagDao().namesForTask("a1"))
        assertEquals(41L, cursor)
        assertTrue(server.requests.any { it.url.encodedPath == "/v1/sync/snapshot" })
        assertTrue(server.requests.all { it.headers["Taskly-Sync-Version"] == "2" }, "SYNC-7 header on every request")
        assertTrue(progress.isNotEmpty(), "the cursor is stored right after the snapshot")

        // AND-S7: offline full-text search finds a task by the text of its note
        assertEquals(listOf("a2"), db.taskDao().search(user, "бюджет").map { it.id })
        assertEquals(listOf("a1"), db.taskDao().search(user, "молок*").map { it.id })
    }

    @Test
    fun delta_appliesEveryEntity_butNeverOverwritesUnsentLocalEdits() = runBlocking<Unit> {
        server.changes = listOf(
            change(1, "task", "t1", "create", """{"name":"С веба","list_id":"L1","location_id":"P1","postponed_count":2}"""),
            change(2, "location", "P1", "create", """{"name":"Дом"}"""),
            change(3, "task", "t1", "tag_add", """{"tag":"звонки"}"""),
            change(4, "note", "n1", "create", """{"task_id":"t1","content":"код домофона 42"}"""),
            change(5, "attachment", "f1", "create", """{"task_id":"t1","name":"План","url":"https://example.com/p"}"""),
            change(6, "task", "t1", "delete", "{}"),
            change(7, "task", "t1", "restore", """{"name":"С веба"}"""),
        )
        assertEquals(7, engine.pull(user, 0, "t"))
        val t = assertNotNull(db.taskDao().getById("t1"))
        assertNull(t.deletedAt, "restore brings the task back")
        assertEquals("P1", t.locationId)
        assertEquals(2, t.postponedCount)
        assertEquals(listOf("звонки"), db.tagDao().namesForTask("t1"))
        assertEquals("код домофона 42", db.noteDao().forTaskNow("t1").single().content)
        assertEquals("План", db.attachmentDao().forTask("t1").first().single().name)
        assertEquals(listOf("t1"), db.taskDao().search(user, "домофона").map { it.id })

        // A local rename is queued; a server rename of the same task must not overwrite it
        repo.rename(t, user, "Моё название")
        server.changes = server.changes + change(8, "task", "t1", "update", """{"name":"Чужое"}""")
        engine.pull(user, 7, "t")
        assertEquals("Моё название", db.taskDao().getById("t1")?.name)
    }

    @Test
    fun conflictOnName_isRecorded_andKeepMineResendsIt() = runBlocking<Unit> {
        val t = repo.createTask(user, "Старое")
        engine.push("t")
        repo.rename(db.taskDao().getById(t.id)!!, user, "Моё")
        server.conflictFor = setOf(t.id)
        server.serverTasks[t.id] = task(t.id, "С ноутбука")

        engine.push("t")
        val c = db.conflictDao().all().single()
        assertEquals("Моё", c.localValue)
        assertEquals("С ноутбука", c.serverValue)
        assertEquals("С ноутбука", db.taskDao().getById(t.id)?.name, "the server's value is shown meanwhile")

        repo.resolveConflict(user, c, keepMine = true)
        assertTrue(db.conflictDao().all().isEmpty())
        assertEquals("Моё", db.taskDao().getById(t.id)?.name)
        val op = db.syncQueueDao().getPending().last()
        assertEquals("update", op.operationType)
        assertEquals("Моё", json.parseToJsonElement(op.fields).jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun noteEdits_carryTheirBaseVersion() = runBlocking<Unit> {
        val t = repo.createTask(user, "С заметкой")
        val note = repo.addNote(user, t.id, "v1")
        repo.updateNote(user, note, "v2")
        engine.push("t")
        val update = server.pushed.last { it["entity"]!!.jsonPrimitive.content == "note" && it["type"]!!.jsonPrimitive.content == "update" }
        assertEquals(1, update["baseVersion"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun expiredToken_isRefreshedOnce_andTheCycleCompletes() = runBlocking<Unit> {
        repo.createTask(user, "После обновления токена")
        server.validToken = "fresh"
        server.snapshotPages = listOf(snapshotPage(seq = 0, tasks = emptyList(), next = null))
        engine.syncNow(user, tokens, 0)
        assertEquals(1, tokens.refreshes)
        assertTrue(db.syncQueueDao().getPending().isEmpty())
    }

    @Test
    fun smartLists_useTheSharedQueryLanguage() = runBlocking<Unit> {
        val a = repo.create(user, TaskRepository.NewTask("Срочный отчёт", priority = 1, tags = listOf("работа")))
        repo.create(user, TaskRepository.NewTask("Хлеб", priority = 3))
        val done = repo.create(user, TaskRepository.NewTask("Старое", priority = 1, tags = listOf("работа")))
        repo.setCompleted(done, user, true)

        assertEquals(listOf(a.id), SmartLists.run(db, user, "priority:1 AND tag:работа AND completed:false", TimeZone.UTC).map { it.id })
        assertEquals(2, SmartLists.run(db, user, "NOT priority:3", TimeZone.UTC).size)
        assertNotNull(SmartLists.problem("priority:1 AND ("))
    }

    @Test
    fun smartAdd_usesTheSharedParser() = runBlocking<Unit> {
        val t = assertNotNull(repo.createFromSmartAdd(user, "Купить молоко !2 #покупки @Магазин", zone = TimeZone.UTC))
        assertEquals("Купить молоко", t.name)
        assertEquals(2, t.priority)
        assertEquals(listOf("покупки"), db.tagDao().namesForTask(t.id), "an unknown #word becomes a tag")
        assertEquals("Магазин", db.locationDao().allNow(user).single().name)
        assertEquals(db.locationDao().allNow(user).single().id, t.locationId)
    }

    // ── Scripted server ──

    private fun task(id: String, name: String) =
        """{"id":"$id","name":"$name","listId":"L1","version":2,"createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z","tags":${if (id == "a1") "[\"T1\"]" else "[]"}}"""

    private fun snapshotPage(seq: Long?, tasks: List<String>, next: String?, extra: String = "") =
        """{${extra}"seq":${seq ?: "null"},"tasks":[${tasks.joinToString(",")}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}"""

    private fun change(seq: Long, entity: String, id: String, op: String, payload: String) =
        """{"seq":$seq,"scopeId":"user:$user","entity":"$entity","entityId":"$id","op":"$op","payload":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(payload))},"actorId":"$user","ts":"2026-10-18T09:00:00Z"}"""

    private inner class FakeServer {
        val requests = mutableListOf<HttpRequestData>()
        val pushed = mutableListOf<JsonObject>()
        var snapshotPages: List<String> = emptyList()
        var changes: List<String> = emptyList()
        var conflictFor: Set<String> = emptySet()
        val serverTasks = mutableMapOf<String, String>()
        var validToken: String? = null

        suspend fun handle(scope: MockRequestHandleScope, req: HttpRequestData): HttpResponseData = with(scope) {
            requests += req
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            val auth = req.headers[HttpHeaders.Authorization]
            if (validToken != null && auth != "Bearer $validToken") return respond("""{"error":"expired"}""", HttpStatusCode.Unauthorized, headers)
            val path = req.url.encodedPath
            when {
                path == "/v1/sync" -> {
                    val body = json.parseToJsonElement((req.body as TextContent).text).jsonObject
                    val ops = body["ops"]!!.jsonArray.map { it.jsonObject }
                    pushed += ops
                    val results = ops.joinToString(",") {
                        val id = it["entityId"]!!.jsonPrimitive.content
                        val status = if (id in conflictFor && it["type"]!!.jsonPrimitive.content == "update") "conflict_resolved" else "ok"
                        """{"opId":"${it["opId"]!!.jsonPrimitive.content}","status":"$status","entityId":"$id","version":3}"""
                    }
                    respond("""{"results":[$results]}""", HttpStatusCode.OK, headers)
                }
                path == "/v1/sync/snapshot" -> {
                    val cursor = req.url.parameters["cursor"]
                    val page = if (cursor == null) snapshotPages.first() else snapshotPages[1]
                    respond(page, HttpStatusCode.OK, headers)
                }
                path == "/v1/sync/changes" -> {
                    val after = req.url.parameters["after"]?.toLong() ?: 0
                    val list = changes.filter { json.parseToJsonElement(it).jsonObject["seq"]!!.jsonPrimitive.content.toLong() > after }
                    respond("""{"changes":[${list.joinToString(",")}]}""", HttpStatusCode.OK, headers)
                }
                path.startsWith("/v1/tasks/") -> {
                    val id = path.removePrefix("/v1/tasks/")
                    serverTasks[id]?.let { respond(it, HttpStatusCode.OK, headers) } ?: respond("""{"error":"not found"}""", HttpStatusCode.NotFound, headers)
                }
                else -> respond("""{"error":"no route"}""", HttpStatusCode.NotFound, headers)
            }
        }
    }

    @Suppress("unused")
    private fun JsonArray.ids() = map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Suppress("unused")
    private val epoch = Instant.fromEpochMilliseconds(0)
}
