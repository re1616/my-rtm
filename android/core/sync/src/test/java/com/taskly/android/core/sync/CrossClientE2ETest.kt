package com.taskly.android.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.TasklyApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/**
 * QA-5, Android side: signs in as the account the browser test registered, creates a task
 * offline, then syncs and records when the push was acknowledged. The browser test
 * (`web/e2e/crossClient.pw.ts`) checks that the task appears in the web app within 2 s.
 *
 * Runs only with QA5_DIR (the folder both sides exchange files through) and TASKLY_E2E_URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossClientE2ETest {

    @Test
    fun taskCreatedOfflineIsPushedForTheWebClient() = runBlocking<Unit> {
        val dir = System.getenv("QA5_DIR")
        val baseUrl = System.getenv("TASKLY_E2E_URL")
        assumeTrue("QA5_DIR / TASKLY_E2E_URL not set — cross-client E2E skipped", dir != null && baseUrl != null)
        val creds = File(dir!!, "creds.json")
        // The browser registers the account first
        val deadline = System.currentTimeMillis() + 180_000
        while (!creds.exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        check(creds.exists()) { "the browser test did not write ${creds.path}" }
        val c = Json.parseToJsonElement(creds.readText()).jsonObject
        val email = c.getValue("email").jsonPrimitive.content
        val password = c.getValue("password").jsonPrimitive.content

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).build()
        try {
            val api = TasklyApiClient.create(baseUrl!!)
            val auth = api.login(email, password)
            val engine = SyncEngine(db, api)
            val repo = TaskRepository(db, engine)

            // Offline first: the task and its operation exist only on the device
            val name = "С Android ${System.nanoTime()}"
            repo.createTask(auth.userId, name)
            assertEquals(1, db.syncQueueDao().getPending().size)

            engine.syncNow(auth.userId, auth.accessToken, afterSeq = 0)
            val pushedAt = System.currentTimeMillis()
            assertEquals(0, db.syncQueueDao().getPending().size, "the operation was acknowledged")

            File(dir, "pushed.json").writeText(
                buildJsonObject { put("name", name); put("pushedAt", pushedAt) }.toString(),
            )
        } finally {
            db.close()
        }
    }
}
