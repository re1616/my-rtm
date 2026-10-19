package com.taskly.android.core.sync.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.AccountSettingsDto
import com.taskly.android.core.network.TasklyApiClient
import com.taskly.android.core.sync.R
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.core.sync.SyncSession
import com.taskly.android.core.sync.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One planned reminder. */
data class PlannedReminder(val taskId: String, val title: String, val fireAt: Instant)

/**
 * Which reminders fall into the next [horizon] (AND-N1: alarms are kept 7 days ahead).
 * The same rules as the server scheduler: N minutes before a due time; for a date-only due —
 * the configured time of day in the user's zone.
 */
object ReminderPlanner {
    val HORIZON: Duration = Duration.ofDays(7)

    fun plan(tasks: List<TaskEntity>, settings: AccountSettingsDto, now: Instant, zone: ZoneId, horizon: Duration = HORIZON): List<PlannedReminder> {
        val end = now.plus(horizon)
        val dayTime = runCatching { LocalTime.parse(settings.reminderDayTime) }.getOrDefault(LocalTime.of(8, 0))
        return tasks.asSequence()
            .filter { it.completedAt == null && it.deletedAt == null && it.dueAt != null }
            .flatMap { t ->
                val due = runCatching { Instant.parse(t.dueAt) }.getOrNull() ?: return@flatMap emptySequence()
                val times = if (t.dueHasTime) {
                    settings.reminderOffsets.map { due.minus(Duration.ofMinutes(it.toLong())) }
                } else {
                    val day: LocalDate = due.atZone(zone).toLocalDate()
                    listOf(day.atTime(dayTime).atZone(zone).toInstant())
                }
                times.asSequence().filter { it.isAfter(now) && !it.isAfter(end) }.map { PlannedReminder(t.id, t.name, it) }
            }
            .sortedBy { it.fireAt }
            .toList()
    }
}

/** Reminder defaults of the account, cached for offline planning. */
class ReminderSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("taskly_reminders", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var settings: AccountSettingsDto
        get() = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(AccountSettingsDto.serializer(), it) }.getOrNull() } ?: AccountSettingsDto(timeZone = ZoneId.systemDefault().id)
        set(value) = prefs.edit().putString(KEY, json.encodeToString(AccountSettingsDto.serializer(), value)).apply()

    /** Request codes of the alarms set last time (to cancel the ones no longer needed). */
    var scheduled: Set<String>
        get() = prefs.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SCHEDULED, value).apply()

    private companion object {
        const val KEY = "settings"
        const val KEY_SCHEDULED = "scheduled"
    }
}

/**
 * Sets the alarms (AND-N1/N2): exact `setExactAndAllowWhileIdle` when the app may use exact
 * alarms, otherwise inexact `setAndAllowWhileIdle` — then the server's push reminder is the
 * backup (the device tells the server when it registers for FCM).
 */
object ReminderScheduler {
    const val CHANNEL_ID = "reminders"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ACTION = "action"

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Recomputes and replaces all alarms of the signed-in user. Returns what was set. */
    suspend fun reschedule(context: Context, db: TasklyDatabase = TasklyDatabase.getInstance(context), now: Instant = Instant.now()): List<PlannedReminder> {
        val session = SyncSession(context)
        val store = ReminderSettingsStore(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val userId = session.userId
        val settings = store.settings
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.systemDefault())
        val plan = if (userId == null || !settings.pushReminders) emptyList() else ReminderPlanner.plan(db.taskDao().allLive(userId), settings, now, zone)

        val keep = plan.map { key(it) }.toSet()
        for (old in store.scheduled - keep) am?.cancel(alarmIntent(context, old, null, null))
        val exact = canScheduleExact(context)
        for (r in plan) {
            val pi = alarmIntent(context, key(r), r.taskId, r.title)
            if (exact) am?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.fireAt.toEpochMilli(), pi)
            else am?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.fireAt.toEpochMilli(), pi)
        }
        store.scheduled = keep
        return plan
    }

    private fun key(r: PlannedReminder) = "${r.taskId}@${r.fireAt.toEpochMilli()}"

    private fun alarmIntent(context: Context, key: String, taskId: String?, title: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.taskly.REMINDER"
            data = android.net.Uri.parse("taskly://reminder/$key")
            taskId?.let { putExtra(EXTRA_TASK_ID, it) }
            title?.let { putExtra(EXTRA_TITLE, it) }
        }
        return PendingIntent.getBroadcast(context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    /** Shows the reminder with "Выполнить" / "Отложить на 1 ч" / tap to open (AND-N3). */
    fun notify(context: Context, taskId: String, title: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val id = taskId.hashCode()
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            context, id * 4 + code,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = "com.taskly.NOTIFICATION_ACTION"
                data = android.net.Uri.parse("taskly://task/$taskId/$name")
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_ACTION, name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_TASK_ID, taskId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply {
                if (open != null) setContentIntent(PendingIntent.getActivity(context, id * 4 + 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            .addAction(0, context.getString(R.string.action_complete), action("complete", 1))
            .addAction(0, context.getString(R.string.action_snooze), action("snooze", 2))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

/** An alarm went off: show the reminder (the task may have been completed meanwhile). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val task = TasklyDatabase.getInstance(context).taskDao().getById(taskId)
                if (task != null && task.completedAt == null && task.deletedAt == null) {
                    ReminderScheduler.notify(context, taskId, task.name)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * "Выполнить" / "Отложить на 1 ч" from the notification (AND-N3): applied as ordinary sync
 * operations, without opening the app; the change is sent by WorkManager.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: return
        val action = intent.getStringExtra(ReminderScheduler.EXTRA_ACTION) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                handle(context, taskId, action)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        suspend fun handle(
            context: Context,
            taskId: String,
            action: String,
            db: TasklyDatabase = TasklyDatabase.getInstance(context),
            afterChange: suspend () -> Unit = {
                SyncEngine.requestSync(context)
                ReminderScheduler.reschedule(context, db)
            },
        ) {
            val session = SyncSession(context)
            val userId = session.userId ?: return
            val task = db.taskDao().getById(taskId) ?: return
            val repo = TaskRepository(db, SyncEngine(db, TasklyApiClient.create(session.baseUrl)))
            when (action) {
                "complete" -> repo.setCompleted(task, userId, completed = true)
                "snooze" -> repo.postpone(task, userId, byHour = true)
                else -> return
            }
            NotificationManagerCompat.from(context).cancel(taskId.hashCode())
            afterChange()
        }
    }
}

/** Alarms are lost on reboot and must follow time zone / clock changes (AND-N1). */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                ReminderScheduler.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
