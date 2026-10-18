package com.taskly.android.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.TasklyApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Android ↔ real server round trip (phase-1 acceptance, AND-S1…S4).
 *
 * Runs against the server at TASKLY_E2E_URL (started by CI); skipped when it is not set.
 * Uses the production client, sync engine and repository — no fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealServerSyncE2ETest {

    private val baseUrl: String? = System.getenv("TASKLY_E2E_URL")
    private lateinit var db: TasklyDatabase
    private lateinit var api: TasklyApiClient
    private lateinit var engine: SyncEngine
    private lateinit var repo: TaskRepository
    private val http = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        // CI sets TASKLY_E2E_REQUIRED so a missing server fails the build instead of skipping silently
        if (System.getenv("TASKLY_E2E_REQUIRED") == "1") {
            check(baseUrl != null) { "TASKLY_E2E_REQUIRED=1 but TASKLY_E2E_URL is not set" }
        }
        assumeTrue("TASKLY_E2E_URL is not set — real-server E2E skipped", baseUrl != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).build()
        api = TasklyApiClient.create(baseUrl!!)
        engine = SyncEngine(db, api)
        repo = TaskRepository(db, engine)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        http.close()
    }

    @Test
    fun taskCreatedOffline_reachesServer_andRemoteChangesComeBack() = runBlocking<Unit> {
        val auth = api.register("android-e2e-${System.nanoTime()}@taskly.test", "Android123!")

        // 1. Offline: create and complete locally — nothing is sent yet
        val task = repo.createTask(auth.userId, """Buy "oat" milk""")
        repo.setCompleted(task, auth.userId, completed = true)
        assertEquals(2, db.syncQueueDao().getPending().size)
        assertEquals(HttpStatusCode.NotFound, serverGet(task.id, auth.accessToken).first)

        // 2. Back online: the queue is pushed and fully acknowledged
        assertEquals(2, engine.push(auth.accessToken), "server must accept both ops")
        assertTrue(db.syncQueueDao().getPending().isEmpty())

        // 3. The server has the task under the same id, with the name and completion
        val (status, body) = serverGet(task.id, auth.accessToken)
        assertEquals(HttpStatusCode.OK, status, body)
        val remote = json.parseToJsonElement(body).jsonObject
        assertEquals("""Buy "oat" milk""", remote["name"]!!.jsonPrimitive.content)
        assertTrue(remote["completedAt"].let { it != null && it !is JsonNull }, "completion must be synced: $body")

        // 4. A task created elsewhere (REST, like the web app) arrives with the next pull
        val webTaskId = serverCreate("Added on the web", auth.accessToken)
        val cursor = engine.pull(auth.userId, 0, auth.accessToken)
        assertTrue(cursor > 0)
        assertEquals("Added on the web", assertNotNull(db.taskDao().getById(webTaskId)).name)

        // 5. Pulling again from the cursor brings nothing new and keeps the cursor
        assertEquals(cursor, engine.pull(auth.userId, cursor, auth.accessToken))
    }

    private suspend fun serverGet(id: String, token: String): Pair<HttpStatusCode, String> {
        val resp = http.get("$baseUrl/v1/tasks/$id") { bearerAuth(token) }
        return resp.status to resp.bodyAsText()
    }

    private suspend fun serverCreate(name: String, token: String): String {
        val resp = http.post("$baseUrl/v1/tasks") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name))).toString())
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }
}
