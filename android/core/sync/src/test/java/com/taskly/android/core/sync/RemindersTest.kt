package com.taskly.android.core.sync

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.AccountSettingsDto
import com.taskly.android.core.sync.reminders.NotificationActionReceiver
import com.taskly.android.core.sync.reminders.ReminderPlanner
import com.taskly.android.core.sync.reminders.ReminderScheduler
import com.taskly.android.core.sync.reminders.ReminderSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** On-device reminders (AND-N1…N3): planning, alarms, notification and its actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemindersTest {

    private val user = "6f1c2b9e-0000-4000-8000-0000000000bb"
    private val now = Instant.parse("2030-03-10T09:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow") // UTC+3
    private lateinit var context: Context
    private lateinit var db: TasklyDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TasklyDatabase::class.java).allowMainThreadQueries().build()
        SyncSession(context).signIn("access", "refresh", user)
    }

    @After
    fun tearDown() {
        db.close()
        SyncSession(context).signOut()
    }

    private fun task(id: String, due: String?, hasTime: Boolean, completed: Boolean = false) = TaskEntity(
        id = id, ownerId = user, listId = "inbox", name = "Task $id", dueAt = due, dueHasTime = hasTime,
        completedAt = if (completed) now.toString() else null, createdAt = now.toString(), updatedAt = now.toString(),
    )

    @Test
    fun planUsesOffsetsForTimedTasks_andTheDayTimeForDateOnlyTasks() {
        val settings = AccountSettingsDto(timeZone = zone.id, reminderOffsets = listOf(0, 30), reminderDayTime = "08:00")
        val tasks = listOf(
            task("timed", "2030-03-10T12:00:00Z", hasTime = true),
            // date-only due: stored as local midnight of 11 March in Moscow
            task("day", "2030-03-10T21:00:00Z", hasTime = false),
            task("done", "2030-03-10T12:00:00Z", hasTime = true, completed = true),
            task("past", "2030-03-10T08:00:00Z", hasTime = true),
            task("far", "2030-04-30T12:00:00Z", hasTime = true),
        )
        val plan = ReminderPlanner.plan(tasks, settings, now, zone)
        assertEquals(
            listOf(
                "timed" to Instant.parse("2030-03-10T11:30:00Z"),
                "timed" to Instant.parse("2030-03-10T12:00:00Z"),
                "day" to Instant.parse("2030-03-11T05:00:00Z"), // 08:00 Moscow
            ),
            plan.map { it.taskId to it.fireAt },
        )
        assertTrue(plan.all { Duration.between(now, it.fireAt) <= ReminderPlanner.HORIZON })
    }

    @Test
    fun rescheduleSetsOneAlarmPerReminder_andCancelsOnesNoLongerNeeded() = runBlocking<Unit> {
        ReminderSettingsStore(context).settings = AccountSettingsDto(timeZone = zone.id, reminderOffsets = listOf(15))
        val soon = Instant.now().plus(Duration.ofHours(2))
        db.taskDao().upsert(task("a", soon.toString(), hasTime = true))
        db.taskDao().upsert(task("b", soon.plus(Duration.ofHours(1)).toString(), hasTime = true))
        val am = shadowOf(context.getSystemService(AlarmManager::class.java))

        val planned = ReminderScheduler.reschedule(context, db)
        assertEquals(2, planned.size)
        assertEquals(2, am.scheduledAlarms.size)

        // Completing a task removes its alarm on the next reschedule
        db.taskDao().upsert(task("a", soon.toString(), hasTime = true, completed = true))
        ReminderScheduler.reschedule(context, db)
        assertEquals(1, am.scheduledAlarms.size)

        // Push reminders off: nothing is scheduled
        ReminderSettingsStore(context).settings = AccountSettingsDto(timeZone = zone.id, pushReminders = false)
        ReminderScheduler.reschedule(context, db)
        assertEquals(0, am.scheduledAlarms.size)
    }

    @Test
    fun notificationHasCompleteAndSnoozeActions() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderScheduler.notify(context, "t1", "Call mom")
        val nm = shadowOf(context.getSystemService(NotificationManager::class.java))
        val n = nm.allNotifications.single()
        assertEquals(2, n.actions.size)
        assertNotNull(nm.getNotificationChannel(ReminderScheduler.CHANNEL_ID))
    }

    @Test
    fun notificationActionsCompleteOrSnoozeThroughTheSyncQueue() = runBlocking<Unit> {
        val due = Instant.now().plus(Duration.ofMinutes(10))
        db.taskDao().upsert(task("c", due.toString(), hasTime = true))
        db.taskDao().upsert(task("s", due.toString(), hasTime = true))
        var after = 0

        NotificationActionReceiver.handle(context, "c", "complete", db) { after++ }
        assertNotNull(db.taskDao().getById("c")?.completedAt)

        NotificationActionReceiver.handle(context, "s", "snooze", db) { after++ }
        val snoozed = db.taskDao().getById("s")!!
        assertTrue(Instant.parse(snoozed.dueAt) >= due.plus(Duration.ofMinutes(59)), "snoozed by one hour")

        val ops = db.syncQueueDao().getPending().map { it.entityId to it.operationType }
        assertTrue("c" to "complete" in ops, "complete is queued: $ops")
        assertTrue(ops.any { it.first == "s" }, "snooze is queued: $ops")
        assertEquals(2, after)
    }
}
