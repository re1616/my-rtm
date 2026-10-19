package com.taskly.android.feature.tasks

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskly.android.core.database.ConflictEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.AccountSettingsDto
import com.taskly.android.core.network.AuthResult
import com.taskly.android.core.network.TasklyApiClient
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.core.sync.SyncSession
import com.taskly.android.core.sync.SyncStatus
import com.taskly.android.core.sync.TaskRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sign-in double: no network; records what the UI asked for. */
class FakeAuth(context: Context, var user: String? = USER) : AuthRepository(
    TasklyApiClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })),
    SyncSession(context),
) {
    var requireTotp = false
    var lastTotp: String? = null
    val patches = mutableListOf<JsonObject>()
    var stored = AccountSettingsDto(timeZone = "UTC")

    override fun getUserId(): String? = user
    override fun isLoggedIn() = user != null
    override suspend fun login(email: String, password: String, totpCode: String?): Result<AuthResult> {
        lastTotp = totpCode
        if (requireTotp && totpCode == null) return Result.failure(IllegalStateException("401: {\"code\":\"totp_required\"}"))
        user = USER
        return Result.success(AuthResult("a", "r", USER))
    }
    override suspend fun register(email: String, password: String) = login(email, password, null)
    override suspend fun settings(): Result<AccountSettingsDto> = Result.success(stored)
    override suspend fun updateSettings(patch: JsonObject): Result<AccountSettingsDto> {
        patches += patch
        val offsets = patch["reminderOffsets"]?.let { a -> (a as kotlinx.serialization.json.JsonArray).map { it.toString().toInt() } }
        stored = stored.copy(reminderOffsets = offsets ?: stored.reminderOffsets)
        return Result.success(stored)
    }
    override fun logout() { user = null }

    companion object { const val USER = "6f1c2b9e-0000-4000-8000-0000000000aa" }
}

/**
 * Compose UI of the task screens on the JVM (Robolectric): Room in memory, the real
 * repository and sync queue; only the network is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp") // a typical phone
class MainScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: TasklyDatabase
    private lateinit var repo: TaskRepository
    private lateinit var auth: FakeAuth
    private lateinit var vm: MainViewModel
    private val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    private var syncRequests = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).allowMainThreadQueries().build()
        repo = TaskRepository(db, SyncEngine(db, TasklyApiClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }))))
        auth = FakeAuth(context)
        vm = MainViewModel(db, repo, auth, status, requestSync = { syncRequests++ })
    }

    @After
    fun tearDown() = db.close()

    private fun show() = compose.setContent { TasklyRoot(vm) }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitGone(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun task(name: String) = runBlocking { repo.createTask(FakeAuth.USER, name) }

    @Test
    fun smartAddCreatesTaskWithPriorityAndTag_andUndoRemovesIt() {
        show()
        compose.onNodeWithTag("smart_add").performTextInput("Buy milk !1 #home")
        compose.onNodeWithTag("smart_add_send").performClick()
        waitForText("Buy milk")
        waitForText("#home")
        val created = runBlocking { db.taskDao().allLive(FakeAuth.USER) }.single()
        assertEquals(1, created.priority)
        assertTrue(syncRequests > 0, "a sync is requested after a change")

        compose.onNodeWithText("Undo").performClick()
        waitGone("Buy milk")
    }

    @Test
    fun checkboxCompletesTask_whichMovesToTheCompletedTab() {
        task("Write report")
        show()
        waitForText("Write report")
        compose.onNodeWithContentDescription("Write report").performClick()
        waitGone("Write report")
        assertTrue(runBlocking { db.taskDao().allLive(FakeAuth.USER) }.single().completedAt != null)
        compose.onNodeWithTag("tab_completed").performClick()
        waitForText("Write report")
    }

    @Test
    fun swipeRightCompletes() {
        val t = task("Swipe me")
        show()
        waitForText("Swipe me")
        compose.onNodeWithTag("task_${t.id}").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { runBlocking { db.taskDao().getById(t.id) }?.completedAt != null }
    }

    @Test
    fun taskCardEditsNameAndAddsNote() {
        val t = task("Old name")
        show()
        waitForText("Old name")
        compose.onNodeWithText("Old name").performClick()
        compose.onNodeWithTag("task_detail").assertIsDisplayed()

        compose.onNodeWithTag("detail_name").performScrollTo().performTextReplacement("New name")
        compose.onNodeWithTag("detail_name_save").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { db.taskDao().getById(t.id) }?.name == "New name" }

        compose.onNodeWithTag("add_note").performScrollTo().performTextInput("Call before 5")
        compose.onNodeWithTag("add_note_send").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { db.noteDao().forTaskNow(t.id) }.map { it.content } == listOf("Call before 5") }
        waitForText("Call before 5")

        compose.onNodeWithTag("priority_1").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { db.taskDao().getById(t.id) }?.priority == 1 }

        compose.onNodeWithTag("detail_due").performScrollTo().performTextReplacement("2030-01-15")
        compose.onNodeWithTag("detail_due_save").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { db.taskDao().getById(t.id) }?.dueAt != null }

        compose.onNodeWithTag("back").performClick()
        waitForText("New name")
        compose.onAllNodes(hasTestTag("task_detail")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun conflictDialogLetsTheUserKeepTheServerValue() {
        val t = task("Server name")
        runBlocking {
            db.conflictDao().upsert(ConflictEntity("task", t.id, "name", "My name", "Server name", Instant.now().toString()))
        }
        show()
        waitForText("Changed on another device")
        compose.onNodeWithTag("conflict_mine").assertIsDisplayed()
        compose.onNodeWithTag("keep_server").performClick()
        waitGone("Changed on another device")
        assertTrue(runBlocking { db.conflictDao().all() }.isEmpty())
        assertEquals("Server name", runBlocking { db.taskDao().getById(t.id) }?.name)
    }

    @Test
    fun searchUsesTheSharedQueryLanguage_andShowsQueryErrors() {
        val urgent = task("Urgent thing")
        task("Someday thing")
        runBlocking { repo.update(urgent, FakeAuth.USER) { copy(priority = 1) } }
        show()
        waitForText("Someday thing")
        compose.onNodeWithTag("open_search").performClick()
        compose.onNodeWithTag("search_field").performTextInput("priority:1")
        waitGone("Someday thing")
        compose.onNodeWithText("Urgent thing").assertIsDisplayed()

        compose.onNodeWithTag("search_field").performTextReplacement("(priority:1")
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("query_error")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun offlineBannerShowsQueuedChanges_andSnapshotProgress() {
        task("Queued")
        status.value = SyncStatus.Offline
        show()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Offline — 1 changes", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        status.value = SyncStatus.Snapshot(1500, firstPageDone = true)
        waitForText("1500 loaded", substring = true)
    }

    @Test
    fun signInAsksForTheTwoFactorCode() {
        auth.user = null
        auth.requireTotp = true
        vm = MainViewModel(db, repo, auth, status, requestSync = { syncRequests++ })
        show()
        compose.onNodeWithTag("auth_email").performTextInput("me@example.com")
        compose.onNodeWithTag("auth_password").performTextInput("correct horse")
        compose.onNodeWithTag("auth_submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("auth_totp")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("auth_totp").performTextInput("123456")
        compose.onNodeWithTag("auth_submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("smart_add")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("123456", auth.lastTotp)
    }

    @Test
    fun settingsPatchReminderOffsets() {
        show()
        compose.onNodeWithTag("open_drawer").performClick()
        compose.onNodeWithTag("nav_settings").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.screen.value == MainViewModel.Screen.SETTINGS }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("offset_60")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("offset_60").performScrollTo().performClick()
        compose.waitUntil(5_000) { auth.patches.isNotEmpty() }
        assertEquals("[15,60]", auth.patches.last()["reminderOffsets"].toString())
    }

    @Test
    fun drawerSwitchesToToday_andNewTasksThereGetTodaysDate() {
        show()
        compose.onNodeWithTag("open_drawer").performClick()
        compose.onNodeWithTag("nav_today").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.view.value == TaskView.Sys(TaskView.System.TODAY) }
        compose.waitForIdle()
        compose.onNodeWithTag("smart_add").performTextInput("Pay rent")
        compose.onNodeWithTag("smart_add_send").performClick()
        waitForText("Pay rent")
        compose.waitUntil(5_000) { runBlocking { db.taskDao().allLive(FakeAuth.USER) }.singleOrNull()?.dueAt != null }
        assertNull(runBlocking { db.taskDao().allLive(FakeAuth.USER) }.single().completedAt)
    }
}

/** Expanded width: the list and the task card side by side. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class TwoPaneTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun listAndCardAreShownTogether() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).allowMainThreadQueries().build()
        val repo = TaskRepository(db, SyncEngine(db, TasklyApiClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }))))
        runBlocking { repo.createTask(FakeAuth.USER, "Wide task") }
        val vm = MainViewModel(db, repo, FakeAuth(context), MutableStateFlow(SyncStatus.Idle), requestSync = {})
        compose.setContent { TasklyRoot(vm) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Wide task")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Wide task").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("detail_pane")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task_list").assertIsDisplayed()
        db.close()
    }
}

/** Russian resources (the primary language of the product). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
class RussianUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun screensUseRussianStrings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).allowMainThreadQueries().build()
        val repo = TaskRepository(db, SyncEngine(db, TasklyApiClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }))))
        val vm = MainViewModel(db, repo, FakeAuth(context), MutableStateFlow(SyncStatus.Idle), requestSync = {})
        compose.setContent { TasklyRoot(vm) }
        assertTrue(compose.onAllNodesWithText("Входящие").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Здесь нет задач").assertIsDisplayed()
        compose.onNodeWithContentDescription("Поиск").assertIsDisplayed()
        db.close()
    }
}
