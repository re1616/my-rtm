package com.taskly.server.push

import com.taskly.server.account.AccountService
import com.taskly.server.account.AccountSettings
import com.taskly.server.db.Database
import com.taskly.server.db.DigestLogTable
import com.taskly.server.db.PushSubscriptionsTable
import com.taskly.server.db.RemindersTable
import com.taskly.server.db.TasksTable
import com.taskly.server.db.UsersTable
import com.taskly.server.email.Email
import com.taskly.server.email.Mailer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.Record
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Reminders (WEB-F8, SPEC FR-15/16): for every open task with a due date the user's default
 * offsets give the moments to remind (N minutes before a due time; for a date-only due — the
 * configured time of day). A reminder is sent once: the (task, moment, channel) row inserted
 * into `reminders` is the lock, so restarts and several instances never send twice.
 * The daily email digest lists today's and overdue tasks at the user's chosen time.
 *
 * [tick] does one pass for a given "now" — the background loop calls it every 30 s and tests
 * call it directly.
 */
object ReminderService {
    private val log = LoggerFactory.getLogger("Reminders")

    /** How late a reminder may still go out (server down, previous tick missed). */
    val GRACE: Duration = Duration.ofMinutes(15)

    data class TickResult(val pushes: Int, val emails: Int, val digests: Int)

    fun tick(now: Instant = Instant.now()): TickResult {
        var pushes = 0
        var emails = 0
        val ctx = Database.ctx()
        val nowOdt = now.atOffset(ZoneOffset.UTC)
        // Widest window any setting can produce: offsets up to 7 days before, date-only
        // tasks are stored at local midnight (±14 h from UTC)
        // (Plain-SQL table fields are unqualified, so tasks and users are read separately.)
        val rows = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.COMPLETED_AT.isNull, TasksTable.DELETED_AT.isNull, TasksTable.DUE_AT.isNotNull)
            .and(TasksTable.DUE_AT.between(nowOdt.minusDays(2), nowOdt.plusDays(8)))
            .fetch()
        if (rows.isEmpty()) return TickResult(0, 0, sendDigests(now))
        val users = ctx.selectFrom(UsersTable.TABLE)
            .where(UsersTable.ID.`in`(rows.map { it.get(TasksTable.OWNER_ID) }.toSet()))
            .fetch()
            .associateBy { it.get(UsersTable.ID) }

        val settingsByUser = HashMap<UUID, AccountSettings>()
        for (row in rows) {
            val owner = row.get(TasksTable.OWNER_ID)
            val user = users[owner] ?: continue
            val settings = settingsByUser.getOrPut(owner) { AccountService.settingsOf(user) }
            for (fireAt in fireTimes(row, settings)) {
                if (fireAt.isAfter(now) || fireAt.isBefore(now.minus(GRACE))) continue
                if (settings.pushReminders && claim(row.get(TasksTable.ID), fireAt, "push")) {
                    pushes += sendPush(owner, row, settings.locale)
                }
                if (settings.emailReminders && claim(row.get(TasksTable.ID), fireAt, "email")) {
                    Mailer.send(Email(user.get(UsersTable.EMAIL), "Напоминание: ${row.get(TasksTable.NAME)}", reminderText(row, settings)))
                    emails++
                }
            }
        }
        val digests = sendDigests(now)
        return TickResult(pushes, emails, digests)
    }

    /** Moments to remind about this task under the user's settings. */
    fun fireTimes(task: Record, settings: AccountSettings): List<Instant> {
        val due = task.get(TasksTable.DUE_AT)?.toInstant() ?: return emptyList()
        return if (task.get(TasksTable.DUE_HAS_TIME)) {
            settings.reminderOffsets.map { due.minus(Duration.ofMinutes(it.toLong())) }
        } else {
            val zone = zoneOf(settings)
            val day = due.atZone(zone).toLocalDate()
            listOf(day.atTime(AccountService.parseTime(settings.reminderDayTime)).atZone(zone).toInstant())
        }
    }

    private fun zoneOf(settings: AccountSettings): ZoneId = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneOffset.UTC)

    /** Inserts the "sent" marker; false when another tick/instance already did. */
    private fun claim(taskId: UUID, fireAt: Instant, channel: String): Boolean =
        Database.ctx().insertInto(RemindersTable.TABLE)
            .set(RemindersTable.TASK_ID, taskId)
            .set(RemindersTable.FIRE_AT, fireAt.atOffset(ZoneOffset.UTC))
            .set(RemindersTable.CHANNEL, channel)
            .set(RemindersTable.SENT, true)
            .onConflictDoNothing()
            .execute() > 0

    private fun sendPush(owner: UUID, task: Record, locale: String): Int {
        val sender = WebPush.sender ?: return 0
        val subs = Database.ctx().selectFrom(PushSubscriptionsTable.TABLE).where(PushSubscriptionsTable.USER_ID.eq(owner)).fetch()
        val payload = buildJsonObject {
            put("type", "reminder")
            put("taskId", task.get(TasksTable.ID).toString())
            put("title", task.get(TasksTable.NAME))
            put("locale", locale)
            put("dueAt", task.get(TasksTable.DUE_AT)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("dueHasTime", task.get(TasksTable.DUE_HAS_TIME))
        }.toString()
        var sent = 0
        for (s in subs) {
            val keys = PushSubscriptionKeys(s.get(PushSubscriptionsTable.ENDPOINT), s.get(PushSubscriptionsTable.P256DH), s.get(PushSubscriptionsTable.AUTH))
            when (sender.send(keys, payload)) {
                PushOutcome.SENT -> sent++
                PushOutcome.GONE -> Database.ctx().deleteFrom(PushSubscriptionsTable.TABLE)
                    .where(PushSubscriptionsTable.ID.eq(s.get(PushSubscriptionsTable.ID))).execute()
                PushOutcome.FAILED -> log.warn("Reminder push for task {} failed", task.get(TasksTable.ID))
            }
        }
        return sent
    }

    private fun formatDue(task: Record, settings: AccountSettings): String {
        val due = task.get(TasksTable.DUE_AT)?.atZoneSameInstant(zoneOf(settings)) ?: return ""
        return if (task.get(TasksTable.DUE_HAS_TIME)) due.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        else due.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }

    private fun reminderText(task: Record, settings: AccountSettings): String =
        "${task.get(TasksTable.NAME)}\nСрок: ${formatDue(task, settings)}\n\nОткрыть Taskly: ${com.taskly.server.Config.appUrl}/?task=${task.get(TasksTable.ID)}"

    /** Daily digest for users whose local digest time passed today and who have not got it yet. */
    private fun sendDigests(now: Instant): Int {
        val ctx = Database.ctx()
        var sent = 0
        val users = ctx.selectFrom(UsersTable.TABLE)
            .where(DSL_settingsFlag("digestEnabled"))
            .fetch()
        for (u in users) {
            val settings = AccountService.settingsOf(u)
            if (!settings.digestEnabled) continue
            val zone = zoneOf(settings)
            val local = now.atZone(zone)
            val digestAt = local.toLocalDate().atTime(AccountService.parseTime(settings.digestTime)).atZone(zone).toInstant()
            // Only within a few hours after the chosen time, never for a past day
            if (now.isBefore(digestAt) || now.isAfter(digestAt.plus(Duration.ofHours(6)))) continue
            val userId = u.get(UsersTable.ID)
            val claimed = ctx.insertInto(DigestLogTable.TABLE)
                .set(DigestLogTable.USER_ID, userId)
                .set(DigestLogTable.DAY, local.toLocalDate())
                .onConflictDoNothing()
                .execute() > 0
            if (!claimed) continue
            val text = digestText(userId, local.toLocalDate(), zone, settings) ?: continue
            Mailer.send(Email(u.get(UsersTable.EMAIL), "Taskly: задачи на ${local.toLocalDate().format(DateTimeFormatter.ofPattern("dd.MM"))}", text))
            sent++
        }
        return sent
    }

    @Suppress("FunctionName")
    private fun DSL_settingsFlag(key: String) =
        org.jooq.impl.DSL.condition("({0} ->> {1})::boolean is true", UsersTable.SETTINGS, org.jooq.impl.DSL.inline(key))

    /** null when there is nothing due today or overdue (no empty emails). */
    fun digestText(userId: UUID, day: LocalDate, zone: ZoneId, settings: AccountSettings): String? {
        val endOfDay = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
        val startOfDay = day.atStartOfDay(zone).toOffsetDateTime()
        val tasks = Database.ctx().selectFrom(TasksTable.TABLE)
            .where(TasksTable.OWNER_ID.eq(userId), TasksTable.COMPLETED_AT.isNull, TasksTable.DELETED_AT.isNull)
            .and(TasksTable.DUE_AT.lt(endOfDay))
            .orderBy(TasksTable.DUE_AT, TasksTable.PRIORITY)
            .limit(200)
            .fetch()
        if (tasks.isEmpty()) return null
        val (overdue, today) = tasks.partition { it.get(TasksTable.DUE_AT).isBefore(startOfDay) }
        fun line(t: Record) = buildString {
            append("• ")
            t.get(TasksTable.PRIORITY)?.let { append("!$it ") }
            append(t.get(TasksTable.NAME))
            if (t.get(TasksTable.DUE_HAS_TIME)) append(" — ").append(t.get(TasksTable.DUE_AT).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
        }
        return buildString {
            if (today.isNotEmpty()) {
                append("Сегодня:\n"); today.forEach { append(line(it)).append('\n') }
            }
            if (overdue.isNotEmpty()) {
                if (today.isNotEmpty()) append('\n')
                append("Просрочено:\n"); overdue.forEach { append(line(it)).append(" (срок ").append(formatDue(it, settings)).append(")\n") }
            }
            append("\nОткрыть Taskly: ").append(com.taskly.server.Config.appUrl)
            append("\nОтключить сводку: Настройки → Напоминания.")
        }
    }

    private var job: Job? = null

    /** Background loop (production server only). */
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO), period: Duration = Duration.ofSeconds(30)) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    log.error("Reminder tick failed", e)
                }
                delay(period.toMillis())
            }
        }
    }

    @Suppress("unused")
    private fun OffsetDateTime.utc() = withOffsetSameInstant(ZoneOffset.UTC)
}
