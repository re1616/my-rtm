package com.taskly.server.account

import com.taskly.server.db.Database
import com.taskly.server.db.LocationsTable
import com.taskly.server.db.TagsTable
import com.taskly.server.db.TaskTagsTable
import com.taskly.server.db.TasksTable
import com.taskly.server.db.UsersTable
import com.taskly.server.dto.ListResponse
import com.taskly.server.dto.TagResponse
import com.taskly.server.dto.TaskResponse
import com.taskly.server.errors.NotFoundException
import com.taskly.server.lists.ListService
import com.taskly.server.sync.LocationResponse
import com.taskly.server.tags.TagService
import com.taskly.server.tasks.AttachmentResponse
import com.taskly.server.tasks.NoteResponse
import com.taskly.server.tasks.TaskChildren
import com.taskly.server.tasks.TaskService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.JSONB
import org.jooq.impl.DSL
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** User preferences (WEB-F8, WEB-F9). Time zone and language live in their own columns. */
@Serializable
data class AccountSettings(
    val timeZone: String = "Europe/Moscow",
    val locale: String = "ru",
    /** system | light | dark */
    val theme: String = "system",
    /** Minutes before a due time (tasks with a time). */
    val reminderOffsets: List<Int> = listOf(15),
    /** Local time of the reminder for tasks with a due date only, "HH:mm". */
    val reminderDayTime: String = "08:00",
    val pushReminders: Boolean = true,
    val emailReminders: Boolean = false,
    val digestEnabled: Boolean = false,
    /** Local time of the daily email digest, "HH:mm". */
    val digestTime: String = "07:00",
    /** 1 = Monday, 7 = Sunday. */
    val weekStart: Int = 1,
    val onboarded: Boolean = false,
)

@Serializable
data class SettingsPatch(
    val timeZone: String? = null,
    val locale: String? = null,
    val theme: String? = null,
    val reminderOffsets: List<Int>? = null,
    val reminderDayTime: String? = null,
    val pushReminders: Boolean? = null,
    val emailReminders: Boolean? = null,
    val digestEnabled: Boolean? = null,
    val digestTime: String? = null,
    val weekStart: Int? = null,
    val onboarded: Boolean? = null,
)

@Serializable
data class ExportedAccount(val email: String, val settings: AccountSettings)

@Serializable
data class AccountExport(
    val format: String = "taskly-export/1",
    val exportedAt: String,
    val account: ExportedAccount,
    val lists: List<ListResponse>,
    val tags: List<TagResponse>,
    val locations: List<LocationResponse>,
    /** Every live task, completed ones included; `tags` are tag NAMES here. */
    val tasks: List<TaskResponse>,
    val notes: List<NoteResponse>,
    val attachments: List<AttachmentResponse>,
)

object AccountService {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val hhmm = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")
    private val stored = setOf("theme", "reminderOffsets", "reminderDayTime", "pushReminders", "emailReminders", "digestEnabled", "digestTime", "weekStart", "onboarded")

    fun settings(userId: String): AccountSettings {
        val user = Database.ctx().selectFrom(UsersTable.TABLE).where(UsersTable.ID.eq(UUID.fromString(userId))).fetchOne()
            ?: throw NotFoundException("User not found")
        return settingsOf(user)
    }

    fun settingsOf(user: org.jooq.Record): AccountSettings {
        val extra = runCatching { json.parseToJsonElement(user.get(UsersTable.SETTINGS)?.data() ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val base = runCatching { json.decodeFromJsonElement(AccountSettings.serializer(), JsonObject(extra.filterKeys { it in stored })) }
            .getOrDefault(AccountSettings())
        return base.copy(timeZone = user.get(UsersTable.TZ), locale = user.get(UsersTable.LOCALE))
    }

    fun update(userId: String, patch: SettingsPatch): AccountSettings {
        patch.timeZone?.let { require(runCatching { ZoneId.of(it) }.isSuccess) { "Unknown time zone: $it" } }
        patch.locale?.let { require(it == "ru" || it == "en") { "locale must be ru or en" } }
        patch.theme?.let { require(it in setOf("system", "light", "dark")) { "theme must be system, light or dark" } }
        patch.reminderOffsets?.let { offs ->
            require(offs.size <= 5 && offs.all { it in 0..10_080 }) { "reminderOffsets: up to 5 values, 0…10080 minutes" }
        }
        patch.reminderDayTime?.let { require(hhmm.matches(it)) { "reminderDayTime must be HH:mm" } }
        patch.digestTime?.let { require(hhmm.matches(it)) { "digestTime must be HH:mm" } }
        patch.weekStart?.let { require(it == 1 || it == 7) { "weekStart must be 1 (Monday) or 7 (Sunday)" } }

        val id = UUID.fromString(userId)
        return Database.ctx().transactionResult { cfg ->
            val tx = DSL.using(cfg)
            val user = tx.selectFrom(UsersTable.TABLE).where(UsersTable.ID.eq(id)).forUpdate().fetchOne()
                ?: throw NotFoundException("User not found")
            val current = settingsOf(user)
            val next = current.copy(
                timeZone = patch.timeZone ?: current.timeZone,
                locale = patch.locale ?: current.locale,
                theme = patch.theme ?: current.theme,
                reminderOffsets = patch.reminderOffsets?.distinct()?.sorted() ?: current.reminderOffsets,
                reminderDayTime = patch.reminderDayTime ?: current.reminderDayTime,
                pushReminders = patch.pushReminders ?: current.pushReminders,
                emailReminders = patch.emailReminders ?: current.emailReminders,
                digestEnabled = patch.digestEnabled ?: current.digestEnabled,
                digestTime = patch.digestTime ?: current.digestTime,
                weekStart = patch.weekStart ?: current.weekStart,
                onboarded = patch.onboarded ?: current.onboarded,
            )
            val extra = json.encodeToJsonElement(AccountSettings.serializer(), next).jsonObject.filterKeys { it in stored }
            tx.update(UsersTable.TABLE)
                .set(UsersTable.TZ, next.timeZone)
                .set(UsersTable.LOCALE, next.locale)
                .set(UsersTable.SETTINGS, JSONB.jsonb(JsonObject(extra).toString()))
                .set(UsersTable.UPDATED_AT, OffsetDateTime.now())
                .where(UsersTable.ID.eq(id))
                .execute()
            next
        }
    }

    /** Everything the user owns, as one JSON document (data portability). */
    fun export(userId: String): AccountExport {
        val ownerId = UUID.fromString(userId)
        val ctx = Database.ctx()
        val user = ctx.selectFrom(UsersTable.TABLE).where(UsersTable.ID.eq(ownerId)).fetchOne()
            ?: throw NotFoundException("User not found")
        val tasks = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.OWNER_ID.eq(ownerId), TasksTable.DELETED_AT.isNull)
            .orderBy(TasksTable.CREATED_AT)
            .fetch()
        val ids = tasks.map { it.get(TasksTable.ID) }
        val tagNames = if (ids.isEmpty()) emptyMap() else
            ctx.select(TaskTagsTable.TASK_ID, TagsTable.NAME)
                .from(TaskTagsTable.TABLE).join(TagsTable.TABLE).on(TagsTable.ID.eq(TaskTagsTable.TAG_ID))
                .where(TaskTagsTable.TASK_ID.`in`(ids))
                .fetch()
                .groupBy({ it.value1() }, { it.value2() })
        return AccountExport(
            exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            account = ExportedAccount(user.get(UsersTable.EMAIL), settingsOf(user)),
            lists = ListService.list(userId),
            tags = TagService.list(userId),
            locations = ctx.select(LocationsTable.ID, LocationsTable.NAME).from(LocationsTable.TABLE)
                .where(LocationsTable.OWNER_ID.eq(ownerId))
                .fetch { LocationResponse(it.value1().toString(), it.value2()) },
            tasks = tasks.map { TaskService.toResponse(it, tagNames[it.get(TasksTable.ID)] ?: emptyList()) },
            notes = TaskChildren.notesFor(ctx, ids),
            attachments = TaskChildren.attachmentsFor(ctx, ids),
        )
    }

    fun parseTime(hhmmValue: String): LocalTime = LocalTime.parse(hhmmValue)
}
