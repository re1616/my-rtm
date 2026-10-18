package com.taskly.server.tasks

import com.taskly.server.db.Database
import com.taskly.server.db.ListsTable
import com.taskly.server.db.NotesTable
import com.taskly.server.db.TagsTable
import com.taskly.server.db.TaskTagsTable
import com.taskly.server.db.TasksTable
import com.taskly.server.db.UsersTable
import com.taskly.server.dto.*
import com.taskly.server.errors.ConflictException
import com.taskly.server.errors.ForbiddenException
import com.taskly.server.errors.NotFoundException
import com.taskly.server.sync.ChangeLog
import com.taskly.shared.domain.model.RepeatMode
import com.taskly.shared.query.Param
import com.taskly.shared.query.QueryParser
import com.taskly.shared.query.SqlBuilder
import com.taskly.shared.query.SqlRenderer
import com.taskly.shared.query.TaskEvalContext
import com.taskly.shared.recurrence.RRule
import com.taskly.shared.recurrence.Recurrence
import com.taskly.shared.smartadd.SmartAddParser
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Task service: REST CRUD + the transactional building blocks reused by sync.
 *
 * Domain logic lives in the shared KMP module and is called from here:
 * - smart lists / `?q=` → [QueryParser] + [SqlBuilder] + [SqlRenderer];
 * - next instance of a repeating task → [RRule] + [Recurrence] (every/after, BYDAY, UNTIL, DST);
 * - Smart Add → [SmartAddParser].
 *
 * Every mutation writes to change_log in the same transaction; WebSocket signals are
 * published only after commit.
 */
object TaskService {
    private val logger = LoggerFactory.getLogger("TaskService")
    private val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    const val DEFAULT_TZ = "Europe/Moscow"

    // ============================================================
    // REST API
    // ============================================================

    fun create(userId: String, request: CreateTaskRequest): TaskResponse {
        val ownerId = UUID.fromString(userId)
        val changes = mutableListOf<ChangeLog.Change>()

        val response = Database.ctx().transactionResult { config ->
            val tx = DSL.using(config)
            val tzStr = userTz(tx, ownerId)
            val zoneId = ZoneId.of(tzStr)

            // Smart Add: parse request.name with the shared SmartAddParser
            val parsed = if (request.smartAdd == true) {
                val listNames = tx.select(ListsTable.NAME).from(ListsTable.TABLE)
                    .where(ListsTable.OWNER_ID.eq(ownerId), ListsTable.DELETED_AT.isNull)
                    .fetchInto(String::class.java).toSet()
                val tagNames = tx.select(TagsTable.NAME).from(TagsTable.TABLE)
                    .where(TagsTable.OWNER_ID.eq(ownerId))
                    .fetchInto(String::class.java).toSet()
                val today = java.time.LocalDate.now(zoneId).toKotlinLocalDate()
                SmartAddParser().parse(request.name, today, kotlinx.datetime.TimeZone.of(tzStr), listNames, tagNames)
            } else null

            fun parsedToOffset(p: com.taskly.shared.smartadd.ParsedDue): OffsetDateTime =
                Instant.ofEpochSecond(p.instant.epochSeconds, p.instant.nanosecondsOfSecond.toLong())
                    .atZone(zoneId).toOffsetDateTime()

            val effectiveName = (parsed?.name?.takeIf { it.isNotBlank() } ?: request.name).trim()
            require(effectiveName.isNotEmpty()) { "name must not be empty" }

            val parsedListName = parsed?.listName
            val listId = when {
                parsedListName != null -> findListByName(tx, ownerId, parsedListName) ?: inboxId(tx, ownerId)
                request.listId != null -> UUID.fromString(request.listId).also { requireOwnList(tx, ownerId, it) }
                else -> inboxId(tx, ownerId)
            }

            val rrule = (parsed?.repeat?.rrule ?: request.rrule)?.let { normalizeRrule(it) }
            val repeatMode = (parsed?.repeat?.mode?.name?.lowercase() ?: request.repeatMode)?.lowercase()
            require(repeatMode == null || repeatMode == "every" || repeatMode == "after") {
                "repeatMode must be 'every' or 'after'"
            }
            val priority = parsed?.priority?.level ?: request.priority
            require(priority == null || priority in 1..3) { "priority must be 1, 2, 3 or null" }

            val now = OffsetDateTime.now()
            val record = tx.insertInto(TasksTable.TABLE)
                .set(TasksTable.OWNER_ID, ownerId)
                .set(TasksTable.LIST_ID, listId)
                .set(TasksTable.NAME, effectiveName)
                .set(TasksTable.PRIORITY, priority)
                .set(TasksTable.DUE_AT, parsed?.due?.let { parsedToOffset(it) } ?: request.dueAt?.let { TaskFields.parseTimestamp(it, "dueAt") })
                .set(TasksTable.DUE_HAS_TIME, parsed?.due?.hasTime ?: request.dueHasTime ?: false)
                .set(TasksTable.START_AT, parsed?.start?.let { parsedToOffset(it) } ?: request.startAt?.let { TaskFields.parseTimestamp(it, "startAt") })
                .set(TasksTable.ESTIMATE_MIN, parsed?.estimateMin ?: request.estimateMin)
                .set(TasksTable.RRULE, rrule)
                .set(TasksTable.REPEAT_MODE, if (rrule != null) (repeatMode ?: "every") else repeatMode)
                .set(TasksTable.URL, request.url)
                .set(TasksTable.CREATED_AT, now)
                .set(TasksTable.UPDATED_AT, now)
                .returning(TasksTable.ALL)
                .fetchOne()!!
            val taskId = record.get(TasksTable.ID)

            val tagNamesToLink = parsed?.tags ?: request.tags.orEmpty()
            for (tagName in tagNamesToLink) {
                val tagId = findTagByName(tx, ownerId, tagName) ?: continue
                linkTag(tx, taskId, tagId)
            }
            parsed?.note?.takeIf { it.isNotBlank() }?.let { note ->
                tx.insertInto(NotesTable.TABLE)
                    .set(NotesTable.TASK_ID, taskId)
                    .set(NotesTable.CONTENT, note)
                    .execute()
            }

            changes += ChangeLog.append(tx, ownerId, "task", taskId, "create", snapshotPayload(record), record.get(TasksTable.VERSION))
            toResponse(record, tagIds(tx, taskId))
        }
        ChangeLog.publish(changes)
        return response
    }

    fun get(userId: String, taskId: String): TaskResponse? {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)
        val id = parseUuid(taskId)
        val rec = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.ID.eq(id))
            .and(TasksTable.OWNER_ID.eq(ownerId))
            .and(TasksTable.DELETED_AT.isNull)
            .fetchOne() ?: return null
        return toResponse(rec, tagIds(ctx, id))
    }

    /**
     * Lists tasks. `query` is the smart-list query language (SPEC F-3) — the same AST that the
     * client evaluates as a predicate is rendered to SQL here, so results are identical.
     */
    fun list(
        userId: String,
        listId: String? = null,
        query: String? = null,
        cursor: String? = null,
        limit: Int = 50,
    ): Pair<List<TaskResponse>, String?> {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        var condition: Condition = TasksTable.OWNER_ID.eq(ownerId).and(TasksTable.DELETED_AT.isNull)
        if (listId != null) condition = condition.and(TasksTable.LIST_ID.eq(parseUuid(listId)))
        if (!query.isNullOrBlank()) condition = condition.and(smartQueryCondition(query, userTz(ctx, ownerId)))
        if (cursor != null) {
            val cursorTime = try { OffsetDateTime.parse(cursor) } catch (e: Exception) {
                throw IllegalArgumentException("Invalid cursor")
            }
            condition = condition.and(TasksTable.UPDATED_AT.lt(cursorTime))
        }

        val records = ctx.selectFrom(TasksTable.TABLE)
            .where(condition)
            .orderBy(TasksTable.UPDATED_AT.desc())
            .limit(limit + 1)
            .fetch()
        val hasMore = records.size > limit
        val page = if (hasMore) records.subList(0, limit) else records
        val tasks = page.map { toResponse(it, tagIds(ctx, it.get(TasksTable.ID))) }
        return tasks to (if (hasMore && tasks.isNotEmpty()) tasks.last().updatedAt else null)
    }

    fun update(userId: String, taskId: String, request: UpdateTaskRequest): TaskResponse {
        val ownerId = UUID.fromString(userId)
        val id = parseUuid(taskId)
        val changes = mutableListOf<ChangeLog.Change>()

        val response = Database.ctx().transactionResult { config ->
            val tx = DSL.using(config)
            val existing = lockOwnTask(tx, ownerId, id, includeDeleted = false)
            if (existing.get(TasksTable.VERSION) != request.version) {
                throw ConflictException("Version conflict: expected ${existing.get(TasksTable.VERSION)} but got ${request.version}")
            }

            val fields = buildJsonObject {
                request.name?.let { put("name", it) }
                request.listId?.let { put("list_id", it) }
                request.priority?.let { put("priority", it) }
                request.dueAt?.let { put("due_at", it) }
                request.dueHasTime?.let { put("due_has_time", it) }
                request.startAt?.let { put("start_at", it) }
                request.estimateMin?.let { put("estimate_min", it) }
                request.rrule?.let { put("rrule", normalizeRrule(it)) }
                request.repeatMode?.let { put("repeat_mode", it) }
                request.url?.let { put("url", it) }
                request.completedAt?.let { put("completed_at", it) }
            }
            val columns = TaskFields.toColumns(fields) { requireOwnList(tx, ownerId, it) }
            val versions = bumpFieldVersions(existing, fields.keys)
            val newVersion = existing.get(TasksTable.VERSION) + 1

            val updated = tx.update(TasksTable.TABLE)
                .set(columns)
                .set(TasksTable.FIELD_VERSIONS, versions)
                .set(TasksTable.VERSION, newVersion)
                .set(TasksTable.UPDATED_AT, OffsetDateTime.now())
                .where(TasksTable.ID.eq(id))
                .returning(TasksTable.ALL)
                .fetchOne()!!

            changes += ChangeLog.append(tx, ownerId, "task", id, "update", JsonObject(fields), newVersion)
            toResponse(updated, tagIds(tx, id))
        }
        ChangeLog.publish(changes)
        return response
    }

    fun delete(userId: String, taskId: String) {
        val ownerId = UUID.fromString(userId)
        val id = parseUuid(taskId)
        val changes = mutableListOf<ChangeLog.Change>()
        Database.ctx().transaction { config ->
            val tx = DSL.using(config)
            softDeleteInTx(tx, ownerId, id)?.let { changes += it }
        }
        ChangeLog.publish(changes)
    }

    /**
     * Completes a task. Idempotent: completing an already completed task returns it unchanged
     * and never creates a second instance of a repeating task (ARCH 5.3).
     */
    fun complete(userId: String, taskId: String): CompleteTaskResponse {
        val ownerId = UUID.fromString(userId)
        val id = parseUuid(taskId)
        val changes = mutableListOf<ChangeLog.Change>()

        val result = Database.ctx().transactionResult { config ->
            val tx = DSL.using(config)
            val outcome = completeInTx(tx, ownerId, id, OffsetDateTime.now())
            changes += outcome.changes
            CompleteTaskResponse(
                task = toResponse(outcome.task, tagIds(tx, id)),
                nextInstance = outcome.nextInstance?.let { toResponse(it, tagIds(tx, it.get(TasksTable.ID))) },
            )
        }
        ChangeLog.publish(changes)
        return result
    }

    // ============================================================
    // Transactional building blocks (also used by SyncService)
    // ============================================================

    data class CompleteOutcome(
        val task: Record,
        val nextInstance: Record?,
        val newlyCompleted: Boolean,
        val changes: List<ChangeLog.Change>,
    )

    /** Locks the task row (FOR UPDATE) so concurrent completions cannot both create an instance. */
    fun completeInTx(tx: DSLContext, ownerId: UUID, id: UUID, completedAt: OffsetDateTime): CompleteOutcome {
        val existing = lockOwnTask(tx, ownerId, id, includeDeleted = false)
        if (existing.get(TasksTable.COMPLETED_AT) != null) {
            return CompleteOutcome(existing, null, newlyCompleted = false, changes = emptyList())
        }

        val newVersion = existing.get(TasksTable.VERSION) + 1
        val completed = tx.update(TasksTable.TABLE)
            .set(TasksTable.COMPLETED_AT, completedAt)
            .set(TasksTable.FIELD_VERSIONS, bumpFieldVersions(existing, setOf("completed_at")))
            .set(TasksTable.VERSION, newVersion)
            .set(TasksTable.UPDATED_AT, OffsetDateTime.now())
            .where(TasksTable.ID.eq(id))
            .returning(TasksTable.ALL)
            .fetchOne()!!

        val changes = mutableListOf(
            ChangeLog.append(
                tx, ownerId, "task", id, "complete",
                buildJsonObject { put("completed_at", completedAt.format(fmt)) }, newVersion,
            )
        )

        val next = createNextInstance(tx, ownerId, completed, completedAt)
        if (next != null) {
            changes += ChangeLog.append(tx, ownerId, "task", next.get(TasksTable.ID), "create", snapshotPayload(next), next.get(TasksTable.VERSION))
        }
        return CompleteOutcome(completed, next, newlyCompleted = true, changes = changes)
    }

    fun uncompleteInTx(tx: DSLContext, ownerId: UUID, id: UUID): ChangeLog.Change? {
        val existing = lockOwnTask(tx, ownerId, id, includeDeleted = false)
        if (existing.get(TasksTable.COMPLETED_AT) == null) return null
        val newVersion = existing.get(TasksTable.VERSION) + 1
        tx.update(TasksTable.TABLE)
            .set(TasksTable.COMPLETED_AT, null as OffsetDateTime?)
            .set(TasksTable.FIELD_VERSIONS, bumpFieldVersions(existing, setOf("completed_at")))
            .set(TasksTable.VERSION, newVersion)
            .set(TasksTable.UPDATED_AT, OffsetDateTime.now())
            .where(TasksTable.ID.eq(id))
            .execute()
        return ChangeLog.append(tx, ownerId, "task", id, "uncomplete", JsonObject(emptyMap()), newVersion)
    }

    /**
     * Soft delete. Throws 404 when the task does not exist for this user.
     * Returns null when it was already deleted (repeat deletes are no-ops).
     */
    fun softDeleteInTx(tx: DSLContext, ownerId: UUID, id: UUID): ChangeLog.Change? {
        val existing = lockOwnTask(tx, ownerId, id, includeDeleted = true)
        if (existing.get(TasksTable.DELETED_AT) != null) return null
        val newVersion = existing.get(TasksTable.VERSION) + 1
        val now = OffsetDateTime.now()
        tx.update(TasksTable.TABLE)
            .set(TasksTable.DELETED_AT, now)
            .set(TasksTable.UPDATED_AT, now)
            .set(TasksTable.FIELD_VERSIONS, bumpFieldVersions(existing, setOf("deleted")))
            .set(TasksTable.VERSION, newVersion)
            .where(TasksTable.ID.eq(id))
            .execute()
        return ChangeLog.append(tx, ownerId, "task", id, "delete", JsonObject(emptyMap()), newVersion)
    }

    /**
     * Next instance of a repeating task, computed by the shared recurrence engine:
     * EVERY → next occurrence after the planned date, AFTER → completion date + interval.
     * The local time of day is preserved in the user's time zone (DST-safe).
     * Tags and notes are copied from the parent (SPEC F-4).
     */
    private fun createNextInstance(tx: DSLContext, ownerId: UUID, parent: Record, completedAt: OffsetDateTime): Record? {
        val rruleStr = parent.get(TasksTable.RRULE) ?: return null
        val rule = RRule.parse(rruleStr.removePrefix("RRULE:")) ?: run {
            logger.warn("Task {} has an invalid rrule '{}', no next instance created", parent.get(TasksTable.ID), rruleStr)
            return null
        }
        val mode = if (parent.get(TasksTable.REPEAT_MODE).equals("after", ignoreCase = true)) RepeatMode.AFTER else RepeatMode.EVERY
        val zone = ZoneId.of(userTz(tx, ownerId))

        val dueAt = parent.get(TasksTable.DUE_AT)
        val dueLocal = dueAt?.atZoneSameInstant(zone)
        val completedDate = completedAt.atZoneSameInstant(zone).toLocalDate()
        val anchor = dueLocal?.toLocalDate() ?: completedDate

        val nextDate = Recurrence.nextInstanceDate(
            rule, mode, anchor.toKotlinLocalDate(), completedDate.toKotlinLocalDate(),
        )?.toJavaLocalDate() ?: return null // series ended (UNTIL)

        val localTime = dueLocal?.toLocalTime() ?: java.time.LocalTime.of(23, 59, 59)
        val nextDue = nextDate.atTime(localTime).atZone(zone).toOffsetDateTime()
        val shiftDays = ChronoUnit.DAYS.between(anchor, nextDate)
        val nextStart = parent.get(TasksTable.START_AT)?.atZoneSameInstant(zone)?.plusDays(shiftDays)?.toOffsetDateTime()

        val next = tx.insertInto(TasksTable.TABLE)
            .set(TasksTable.OWNER_ID, ownerId)
            .set(TasksTable.LIST_ID, parent.get(TasksTable.LIST_ID))
            .set(TasksTable.NAME, parent.get(TasksTable.NAME))
            .set(TasksTable.PRIORITY, parent.get(TasksTable.PRIORITY))
            .set(TasksTable.DUE_AT, nextDue)
            .set(TasksTable.DUE_HAS_TIME, parent.get(TasksTable.DUE_HAS_TIME))
            .set(TasksTable.START_AT, nextStart)
            .set(TasksTable.ESTIMATE_MIN, parent.get(TasksTable.ESTIMATE_MIN))
            .set(TasksTable.RRULE, rruleStr)
            .set(TasksTable.REPEAT_MODE, parent.get(TasksTable.REPEAT_MODE) ?: "every")
            .set(TasksTable.URL, parent.get(TasksTable.URL))
            .set(TasksTable.LOCATION_ID, parent.get(TasksTable.LOCATION_ID))
            .returning(TasksTable.ALL)
            .fetchOne()!!
        val nextId = next.get(TasksTable.ID)
        val parentId = parent.get(TasksTable.ID)

        tx.insertInto(TaskTagsTable.TABLE, TaskTagsTable.TASK_ID, TaskTagsTable.TAG_ID)
            .select(
                tx.select(DSL.inline(nextId), TaskTagsTable.TAG_ID)
                    .from(TaskTagsTable.TABLE)
                    .where(TaskTagsTable.TASK_ID.eq(parentId))
            )
            .execute()
        tx.insertInto(NotesTable.TABLE, NotesTable.TASK_ID, NotesTable.CONTENT)
            .select(
                tx.select(DSL.inline(nextId), NotesTable.CONTENT)
                    .from(NotesTable.TABLE)
                    .where(NotesTable.TASK_ID.eq(parentId))
            )
            .execute()
        return next
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Shared query language → parameterized SQL condition on the `tasks` table. */
    fun smartQueryCondition(query: String, tz: String, now: Instant = Instant.now()): Condition {
        val expr = try {
            QueryParser.parse(query)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid query: ${e.message}")
        }
        val ctx = TaskEvalContext(
            now = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilli()),
            timeZone = kotlinx.datetime.TimeZone.of(tz),
        )
        val pred = try {
            SqlBuilder.build(expr, ctx)
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException("Invalid query: ${e.message}")
        }
        val (sql, params) = SqlRenderer.render(pred, alias = "tasks")
        val bindings = params.map {
            when (it) {
                is Param.Str -> it.value
                is Param.Num -> it.value
                is Param.Ts -> Instant.parse(it.value).atOffset(ZoneOffset.UTC)
            }
        }
        return DSL.condition(sql, *bindings.toTypedArray())
    }

    fun userTz(tx: DSLContext, ownerId: UUID): String =
        tx.select(UsersTable.TZ).from(UsersTable.TABLE).where(UsersTable.ID.eq(ownerId))
            .fetchOneInto(String::class.java) ?: DEFAULT_TZ

    /** Throws 403 when the list belongs to someone else (or does not exist). */
    fun requireOwnList(tx: DSLContext, ownerId: UUID, listId: UUID) {
        val ok = tx.fetchExists(
            tx.selectOne().from(ListsTable.TABLE)
                .where(ListsTable.ID.eq(listId))
                .and(ListsTable.OWNER_ID.eq(ownerId))
                .and(ListsTable.DELETED_AT.isNull)
        )
        if (!ok) throw ForbiddenException("List $listId does not belong to the current user")
    }

    fun inboxId(tx: DSLContext, ownerId: UUID): UUID =
        tx.select(ListsTable.ID).from(ListsTable.TABLE)
            .where(ListsTable.OWNER_ID.eq(ownerId))
            .and(ListsTable.NAME.eq("Inbox"))
            .and(ListsTable.DELETED_AT.isNull)
            .limit(1)
            .fetchOneInto(UUID::class.java)
            ?: tx.insertInto(ListsTable.TABLE)
                .set(ListsTable.OWNER_ID, ownerId)
                .set(ListsTable.NAME, "Inbox")
                .returning(ListsTable.ID)
                .fetchOne()!!
                .get(ListsTable.ID)

    private fun findListByName(tx: DSLContext, ownerId: UUID, name: String): UUID? =
        tx.select(ListsTable.ID).from(ListsTable.TABLE)
            .where(ListsTable.OWNER_ID.eq(ownerId), DSL.lower(ListsTable.NAME).eq(name.lowercase()), ListsTable.DELETED_AT.isNull)
            .limit(1)
            .fetchOneInto(UUID::class.java)

    fun findTagByName(tx: DSLContext, ownerId: UUID, name: String): UUID? =
        tx.select(TagsTable.ID).from(TagsTable.TABLE)
            .where(TagsTable.OWNER_ID.eq(ownerId))
            .and(DSL.lower(TagsTable.NAME).eq(name.lowercase()))
            .fetchOneInto(UUID::class.java)

    fun linkTag(tx: DSLContext, taskId: UUID, tagId: UUID) {
        tx.insertInto(TaskTagsTable.TABLE)
            .set(TaskTagsTable.TASK_ID, taskId)
            .set(TaskTagsTable.TAG_ID, tagId)
            .onConflictDoNothing()
            .execute()
    }

    /** Row lock on an own task; 404 for missing/foreign tasks (no existence leak). */
    fun lockOwnTask(tx: DSLContext, ownerId: UUID, id: UUID, includeDeleted: Boolean): Record {
        var cond = TasksTable.ID.eq(id).and(TasksTable.OWNER_ID.eq(ownerId))
        if (!includeDeleted) cond = cond.and(TasksTable.DELETED_AT.isNull)
        return tx.selectFrom(TasksTable.TABLE).where(cond).forUpdate().fetchOne()
            ?: throw NotFoundException("Task not found")
    }

    /** field_versions JSONB with the given fields bumped by one (LWW bookkeeping). */
    fun bumpFieldVersions(existing: Record, fields: Set<String>): JSONB {
        val current = parseFieldVersions(existing.get(TasksTable.FIELD_VERSIONS))
        val updated = current.toMutableMap()
        for (f in fields) updated[f] = (current[f] ?: 0L) + 1
        return encodeFieldVersions(updated)
    }

    fun parseFieldVersions(jsonb: JSONB?): Map<String, Long> {
        val raw = jsonb?.data() ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (k, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.let { k to it }
        }.toMap()
    }

    fun encodeFieldVersions(versions: Map<String, Long>): JSONB =
        JSONB.jsonb(buildJsonObject { versions.forEach { (k, v) -> put(k, v) } }.toString())

    fun normalizeRrule(raw: String): String {
        val cleaned = raw.trim().removePrefix("RRULE:")
        val rule = RRule.parse(cleaned) ?: throw IllegalArgumentException("Invalid rrule: $raw")
        return rule.toRruleString()
    }

    fun tagIds(ctx: DSLContext, taskId: UUID): List<String> =
        ctx.select(TaskTagsTable.TAG_ID).from(TaskTagsTable.TABLE)
            .where(TaskTagsTable.TASK_ID.eq(taskId))
            .fetchInto(UUID::class.java)
            .map { it.toString() }

    /** Full snapshot of task fields (snake_case) for change_log payloads. */
    fun snapshotPayload(rec: Record): JsonObject = buildJsonObject {
        put("name", rec.get(TasksTable.NAME))
        put("list_id", rec.get(TasksTable.LIST_ID).toString())
        put("priority", rec.get(TasksTable.PRIORITY))
        put("due_at", rec.get(TasksTable.DUE_AT)?.format(fmt))
        put("due_has_time", rec.get(TasksTable.DUE_HAS_TIME))
        put("start_at", rec.get(TasksTable.START_AT)?.format(fmt))
        put("estimate_min", rec.get(TasksTable.ESTIMATE_MIN))
        put("rrule", rec.get(TasksTable.RRULE))
        put("repeat_mode", rec.get(TasksTable.REPEAT_MODE))
        put("completed_at", rec.get(TasksTable.COMPLETED_AT)?.format(fmt))
        put("url", rec.get(TasksTable.URL))
    }

    private fun parseUuid(raw: String): UUID = try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        throw NotFoundException("Task not found")
    }

    fun toResponse(rec: Record, tags: List<String>? = null): TaskResponse = TaskResponse(
        id = rec.get(TasksTable.ID).toString(),
        name = rec.get(TasksTable.NAME),
        listId = rec.get(TasksTable.LIST_ID).toString(),
        priority = rec.get(TasksTable.PRIORITY),
        dueAt = rec.get(TasksTable.DUE_AT)?.format(fmt),
        dueHasTime = rec.get(TasksTable.DUE_HAS_TIME),
        startAt = rec.get(TasksTable.START_AT)?.format(fmt),
        estimateMin = rec.get(TasksTable.ESTIMATE_MIN),
        rrule = rec.get(TasksTable.RRULE),
        repeatMode = rec.get(TasksTable.REPEAT_MODE),
        completedAt = rec.get(TasksTable.COMPLETED_AT)?.format(fmt),
        url = rec.get(TasksTable.URL),
        tags = tags,
        version = rec.get(TasksTable.VERSION),
        createdAt = rec.get(TasksTable.CREATED_AT).format(fmt),
        updatedAt = rec.get(TasksTable.UPDATED_AT).format(fmt),
    )
}
