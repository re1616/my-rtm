package com.taskly.server.tasks

import com.taskly.server.db.Database
import com.taskly.server.db.TasksTable
import com.taskly.server.db.ListsTable
import com.taskly.server.db.TagsTable
import com.taskly.server.db.TaskTagsTable
import com.taskly.server.dto.*
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Task service providing CRUD operations and search.
 */
object TaskService {

    /**
     * Create a new task.
     */
    fun create(userId: String, request: CreateTaskRequest): TaskResponse {
        val ownerId = UUID.fromString(userId)

        return Database.ctx().transactionResult { config ->
            val tx = DSL.using(config)

            // Resolve list_id: if not provided, find or create the default "Inbox" list
            val listId = if (request.listId != null) {
                UUID.fromString(request.listId)
            } else {
                val existing = tx.select(ListsTable.ID)
                    .from(ListsTable.TABLE)
                    .where(ListsTable.OWNER_ID.eq(ownerId))
                    .and(ListsTable.NAME.eq("Inbox"))
                    .and(ListsTable.DELETED_AT.isNull)
                    .fetchOneInto(UUID::class.java)

                existing ?: tx.insertInto(ListsTable.TABLE)
                    .set(ListsTable.OWNER_ID, ownerId)
                    .set(ListsTable.NAME, "Inbox")
                    .returning(ListsTable.ID)
                    .fetchOne()!!
                    .get(ListsTable.ID)
            }

            val now = OffsetDateTime.now()

            val record = tx.insertInto(TasksTable.TABLE)
                .set(TasksTable.OWNER_ID, ownerId)
                .set(TasksTable.LIST_ID, listId)
                .set(TasksTable.NAME, request.name)
                .set(TasksTable.PRIORITY, request.priority)
                .set(TasksTable.DUE_AT, request.dueAt?.let { OffsetDateTime.parse(it) })
                .set(TasksTable.DUE_HAS_TIME, request.dueHasTime ?: false)
                .set(TasksTable.START_AT, request.startAt?.let { OffsetDateTime.parse(it) })
                .set(TasksTable.ESTIMATE_MIN, request.estimateMin)
                .set(TasksTable.RRULE, request.rrule)
                .set(TasksTable.REPEAT_MODE, request.repeatMode)
                .set(TasksTable.URL, request.url)
                .set(TasksTable.CREATED_AT, now)
                .set(TasksTable.UPDATED_AT, now)
                .returning(
                    TasksTable.ID, TasksTable.NAME, TasksTable.LIST_ID, TasksTable.PRIORITY,
                    TasksTable.DUE_AT, TasksTable.DUE_HAS_TIME, TasksTable.START_AT,
                    TasksTable.ESTIMATE_MIN, TasksTable.RRULE, TasksTable.REPEAT_MODE,
                    TasksTable.COMPLETED_AT, TasksTable.URL, TasksTable.VERSION,
                    TasksTable.CREATED_AT, TasksTable.UPDATED_AT
                )
                .fetchOne()!!

            val tagIds = mutableListOf<UUID>()
            if (!request.tags.isNullOrEmpty()) {
                for (tagName in request.tags) {
                    val tag = tx.select(TagsTable.ID)
                        .from(TagsTable.TABLE)
                        .where(TagsTable.OWNER_ID.eq(ownerId))
                        .and(DSL.lower(TagsTable.NAME).eq(tagName.lowercase()))
                        .fetchOneInto(UUID::class.java)
                    if (tag != null) {
                        tx.insertInto(TaskTagsTable.TABLE)
                            .set(TaskTagsTable.TASK_ID, record.get(TasksTable.ID))
                            .set(TaskTagsTable.TAG_ID, tag)
                            .onDuplicateKeyIgnore()
                            .execute()
                        tagIds.add(tag)
                    }
                }
            }

            toResponse(record, tagIds.map { it.toString() })
        }
    }

    /**
     * Get a task by ID.
     */
    fun get(userId: String, taskId: String): TaskResponse? {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)
        val id = UUID.fromString(taskId)

        val record = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.ID.eq(id))
            .and(TasksTable.OWNER_ID.eq(ownerId))
            .and(TasksTable.DELETED_AT.isNull)
            .fetchOne() ?: return null

        val tagIds = fetchTagIds(id)
        return toResponse(record, tagIds)
    }

    /**
     * List tasks with optional filters, cursor-based pagination.
     * Supports smart-add parsing via the `query` parameter.
     */
    fun list(
        userId: String,
        listId: String? = null,
        query: String? = null,
        cursor: String? = null,
        limit: Int = 50
    ): Pair<List<TaskResponse>, String?> {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        val records = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.OWNER_ID.eq(ownerId))
            .and(TasksTable.DELETED_AT.isNull)
            .run {
                var q = this
                if (listId != null) {
                    q = q.and(TasksTable.LIST_ID.eq(UUID.fromString(listId)))
                }

                // Parse smart-add query
                if (!query.isNullOrBlank()) {
                    val smartQuery = SmartQueryParser.parse(query)
                    if (smartQuery.name != null) {
                        q = q.and(DSL.lower(TasksTable.NAME).like(DSL.lower("%${smartQuery.name}%")))
                    }
                    if (smartQuery.priority != null) {
                        q = q.and(TasksTable.PRIORITY.eq(smartQuery.priority))
                    }
                    if (smartQuery.dueBefore != null) {
                        q = q.and(TasksTable.DUE_AT.le(smartQuery.dueBefore))
                    }
                    if (smartQuery.dueWithin != null) {
                        val now = OffsetDateTime.now()
                        q = q.and(TasksTable.DUE_AT.ge(now))
                            .and(TasksTable.DUE_AT.le(now.plus(smartQuery.dueWithin)))
                    }
                    if (smartQuery.listName != null) {
                        q = q.and(TasksTable.LIST_ID.`in`(
                            ctx.select(ListsTable.ID).from(ListsTable.TABLE)
                                .where(ListsTable.OWNER_ID.eq(ownerId))
                                .and(DSL.lower(ListsTable.NAME).like(DSL.lower("%${smartQuery.listName}%")))
                        ))
                    }
                    if (smartQuery.completed != null) {
                        val isCompleted = smartQuery.completed == true
                        if (isCompleted) {
                            q = q.and(TasksTable.COMPLETED_AT.isNotNull)
                        } else {
                            q = q.and(TasksTable.COMPLETED_AT.isNull)
                        }
                    }
                    if (smartQuery.isRepeating) {
                        q = q.and(TasksTable.RRULE.isNotNull)
                    }
                }

                // Cursor-based pagination (cursor = updatedAt ISO string)
                if (cursor != null) {
                    val cursorTime = OffsetDateTime.parse(cursor)
                    q = q.and(TasksTable.UPDATED_AT.lt(cursorTime))
                }

                q.orderBy(TasksTable.UPDATED_AT.desc())
                    .limit(limit + 1)
            }
            .fetch()
        val hasMore = records.size > limit
        val pageRecords = if (hasMore) records.subList(0, limit) else records

        val tasks = pageRecords.map { rec ->
            val tags = fetchTagIds(rec.get(TasksTable.ID))
            toResponse(rec, tags)
        }

        val nextCursor = if (hasMore && tasks.isNotEmpty()) {
            tasks.last().updatedAt
        } else null

        return Pair(tasks, nextCursor)
    }

    /**
     * Update a task (optimistic locking via version).
     */
    fun update(userId: String, taskId: String, request: UpdateTaskRequest): TaskResponse {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)
        val id = UUID.fromString(taskId)

        val existing = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.ID.eq(id))
            .and(TasksTable.OWNER_ID.eq(ownerId))
            .and(TasksTable.DELETED_AT.isNull)
            .fetchOne()
            ?: throw IllegalArgumentException("Task not found")

        if (existing.get(TasksTable.VERSION) != request.version) {
            throw IllegalArgumentException("Version conflict: expected ${existing.get(TasksTable.VERSION)} but got ${request.version}")
        }

        val now = OffsetDateTime.now()
        val update = ctx.update(TasksTable.TABLE)
            .set(TasksTable.UPDATED_AT, now)
            .set(TasksTable.VERSION, existing.get(TasksTable.VERSION) + 1)

        request.name?.let { update.set(TasksTable.NAME, it) }
        request.listId?.let { update.set(TasksTable.LIST_ID, UUID.fromString(it)) }
        request.priority?.let { update.set(TasksTable.PRIORITY, it) }
        request.dueAt?.let { update.set(TasksTable.DUE_AT, OffsetDateTime.parse(it)) }
        request.dueHasTime?.let { update.set(TasksTable.DUE_HAS_TIME, it) }
        request.startAt?.let { update.set(TasksTable.START_AT, OffsetDateTime.parse(it)) }
        request.estimateMin?.let { update.set(TasksTable.ESTIMATE_MIN, it) }
        request.rrule?.let { update.set(TasksTable.RRULE, it) }
        request.repeatMode?.let { update.set(TasksTable.REPEAT_MODE, it) }
        request.url?.let { update.set(TasksTable.URL, it) }
        request.completedAt?.let { update.set(TasksTable.COMPLETED_AT, OffsetDateTime.parse(it)) }

        update
            .where(TasksTable.ID.eq(id))
            .and(TasksTable.VERSION.eq(request.version))
            .execute()

        val updated = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.ID.eq(id))
            .fetchOne()!!

        val tags = fetchTagIds(id)
        return toResponse(updated, tags)
    }

    /**
     * Soft-delete a task.
     */
    fun delete(userId: String, taskId: String) {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)

        val deleted = ctx.update(TasksTable.TABLE)
            .set(TasksTable.DELETED_AT, OffsetDateTime.now())
            .set(TasksTable.UPDATED_AT, OffsetDateTime.now())
            .where(TasksTable.ID.eq(UUID.fromString(taskId)))
            .and(TasksTable.OWNER_ID.eq(ownerId))
            .execute()

        if (deleted == 0) {
            throw IllegalArgumentException("Task not found")
        }
    }

    /**
     * Complete a task. If the task has an rrule (repeating), create the next instance.
     */
    fun complete(userId: String, taskId: String): CompleteTaskResponse {
        val ctx = Database.ctx()
        val ownerId = UUID.fromString(userId)
        val id = UUID.fromString(taskId)

        val existing = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.ID.eq(id))
            .and(TasksTable.OWNER_ID.eq(ownerId))
            .and(TasksTable.DELETED_AT.isNull)
            .fetchOne()
            ?: throw IllegalArgumentException("Task not found")

        val now = OffsetDateTime.now()

        ctx.update(TasksTable.TABLE)
            .set(TasksTable.COMPLETED_AT, now)
            .set(TasksTable.UPDATED_AT, now)
            .set(TasksTable.VERSION, existing.get(TasksTable.VERSION) + 1)
            .where(TasksTable.ID.eq(id))
            .execute()

        val completedRecord = ctx.selectFrom(TasksTable.TABLE)
            .where(TasksTable.ID.eq(id))
            .fetchOne()!!

        // Create next instance if repeating
        var nextInstance: TaskResponse? = null
        val rrule = existing.get(TasksTable.RRULE)
        if (rrule != null) {
            val repeatMode = existing.get(TasksTable.REPEAT_MODE)
            val nextDueAt = calculateNextDueDate(
                existing.get(TasksTable.DUE_AT),
                rrule,
                repeatMode,
                existing.get(TasksTable.COMPLETED_AT) ?: now
            )

            val newRecord = ctx.insertInto(TasksTable.TABLE)
                .set(TasksTable.OWNER_ID, ownerId)
                .set(TasksTable.LIST_ID, existing.get(TasksTable.LIST_ID))
                .set(TasksTable.NAME, existing.get(TasksTable.NAME))
                .set(TasksTable.PRIORITY, existing.get(TasksTable.PRIORITY))
                .set(TasksTable.DUE_AT, nextDueAt)
                .set(TasksTable.DUE_HAS_TIME, existing.get(TasksTable.DUE_HAS_TIME))
                .set(TasksTable.START_AT, existing.get(TasksTable.START_AT))
                .set(TasksTable.ESTIMATE_MIN, existing.get(TasksTable.ESTIMATE_MIN))
                .set(TasksTable.RRULE, rrule)
                .set(TasksTable.REPEAT_MODE, repeatMode)
                .set(TasksTable.URL, existing.get(TasksTable.URL))
                .returning(
                    TasksTable.ID, TasksTable.NAME, TasksTable.LIST_ID, TasksTable.PRIORITY,
                    TasksTable.DUE_AT, TasksTable.DUE_HAS_TIME, TasksTable.START_AT,
                    TasksTable.ESTIMATE_MIN, TasksTable.RRULE, TasksTable.REPEAT_MODE,
                    TasksTable.COMPLETED_AT, TasksTable.URL, TasksTable.VERSION,
                    TasksTable.CREATED_AT, TasksTable.UPDATED_AT
                )
                .fetchOne()!!

            val tags = fetchTagIds(id)
            nextInstance = toResponse(newRecord, tags)
        }

        val completedTags = fetchTagIds(id)
        return CompleteTaskResponse(
            task = toResponse(completedRecord, completedTags),
            nextInstance = nextInstance
        )
    }

    /**
     * Fetch tag IDs for a task.
     */
    private fun fetchTagIds(taskId: UUID): List<String> {
        val ctx = Database.ctx()
        return ctx.select(TagsTable.ID)
            .from(TagsTable.TABLE)
            .join(TaskTagsTable.TABLE).on(TagsTable.ID.eq(TaskTagsTable.TAG_ID))
            .where(TaskTagsTable.TASK_ID.eq(taskId))
            .fetchInto(UUID::class.java)
            .map { it.toString() }
    }

    /**
     * Convert a jOOQ record to TaskResponse.
     */
    private fun toResponse(rec: org.jooq.Record, tags: List<String>? = null): TaskResponse {
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        return TaskResponse(
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
            updatedAt = rec.get(TasksTable.UPDATED_AT).format(fmt)
        )
    }

    /**
     * Very basic RRule next-date calculator.
     * Handles simple daily, weekly, monthly cases.
     */
    private fun calculateNextDueDate(
        currentDueAt: OffsetDateTime?,
        rrule: String,
        repeatMode: String?,
        completedAt: OffsetDateTime
    ): OffsetDateTime? {
        val base = currentDueAt ?: completedAt
        return when {
            rrule.uppercase().startsWith("FREQ=DAILY") || rrule.contains("DAILY") -> base.plusDays(1)
            rrule.uppercase().startsWith("FREQ=WEEKLY") || rrule.contains("WEEKLY") -> base.plusWeeks(1)
            rrule.uppercase().startsWith("FREQ=MONTHLY") || rrule.contains("MONTHLY") -> base.plusMonths(1)
            rrule.uppercase().startsWith("FREQ=YEARLY") || rrule.contains("YEARLY") -> base.plusYears(1)
            rrule.toIntOrNull() != null -> {
                // Simple numeric interval in days
                val interval = rrule.toInt()
                val days = if (repeatMode == "after") (completedAt.toLocalDate().toEpochDay() - (currentDueAt?.toLocalDate()?.toEpochDay() ?: 0L)).toInt() else interval
                base.plusDays(maxOf(days, interval).toLong())
            }
            else -> base.plusDays(1)
        }
    }
}

/**
 * Simple smart-add query parser.
 * Parses patterns like:
 *   "Buy milk p:1" -> priority 1
 *   "Grocery list:food" -> filter by list "food"
 *   "dueBefore:2026-10-20"
 *   "dueWithin:2d" (2 days)
 *   "completed:true"
 *   "isRepeating:true"
 */
object SmartQueryParser {
    data class SmartQuery(
        var name: String? = null,
        var priority: Int? = null,
        var listName: String? = null,
        var dueBefore: OffsetDateTime? = null,
        var dueWithin: java.time.Duration? = null,
        var completed: Boolean? = null,
        var isRepeating: Boolean = false
    )

    fun parse(input: String): SmartQuery {
        val sq = SmartQuery()
        val tokens = input.split(" ").toMutableList()
        val nameParts = mutableListOf<String>()

        for (token in tokens) {
            when {
                token.matches(Regex("^p(?:riority)?:(\\d+)")) -> {
                    val v = token.split(":")[1].toIntOrNull()
                    if (v in 1..3) sq.priority = v
                }
                token.matches(Regex("^list:(.+)")) -> {
                    sq.listName = token.substringAfter(":")
                }
                token.matches(Regex("^dueBefore:(.+)")) -> {
                    val value = token.substringAfter(":")
                    sq.dueBefore = try { OffsetDateTime.parse(value) } catch (_: Exception) { null }
                }
                token.matches(Regex("^dueWithin:(.+)")) -> {
                    val value = token.substringAfter(":")
                    val match = Regex("(\\d+)([dhms])").find(value)
                    if (match != null) {
                        val amount = match.groupValues[1].toLong()
                        sq.dueWithin = when (match.groupValues[2]) {
                            "d" -> java.time.Duration.ofDays(amount)
                            "h" -> java.time.Duration.ofHours(amount)
                            "m" -> java.time.Duration.ofMinutes(amount)
                            "s" -> java.time.Duration.ofSeconds(amount)
                            else -> null
                        }
                    }
                }
                token.matches(Regex("^completed:(true|false|yes|no)")) -> {
                    sq.completed = token.substringAfter(":") in listOf("true", "yes")
                }
                token.matches(Regex("^isRepeating:(true|false)")) -> {
                    sq.isRepeating = token.substringAfter(":") == "true"
                }
                else -> nameParts.add(token)
            }
        }

        sq.name = nameParts.joinToString(" ").takeIf { it.isNotBlank() }
        return sq
    }
}