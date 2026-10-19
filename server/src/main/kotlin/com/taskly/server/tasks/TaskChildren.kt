package com.taskly.server.tasks

import com.taskly.server.db.AttachmentsTable
import com.taskly.server.db.ChangeLogTable
import com.taskly.server.db.Database
import com.taskly.server.db.NotesTable
import com.taskly.server.db.TagsTable
import com.taskly.server.db.TaskTagsTable
import com.taskly.server.db.TasksTable
import com.taskly.server.errors.ForbiddenException
import com.taskly.server.errors.NotFoundException
import com.taskly.server.sync.ChangeLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.Record
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
data class NoteResponse(val id: String, val taskId: String, val content: String, val version: Long, val createdAt: String, val updatedAt: String)

@Serializable
data class AttachmentResponse(val id: String, val taskId: String, val name: String, val url: String, val version: Long, val createdAt: String)

@Serializable
data class HistoryEntry(val seq: Long, val entity: String, val entityId: String, val op: String, val payload: String, val ts: String)

/**
 * Task-owned entities of the task card (WEB-F3): notes (markdown), link attachments, subtasks
 * and the change history. Notes and attachments have no owner column: ownership always goes
 * through the task they belong to, so every operation first locks a task of the caller.
 */
object TaskChildren {
    const val MAX_NOTE_LENGTH = 100_000

    /** Set by [applyNoteOp] when an edit lost to a newer server version (per call, single thread). */
    private val conflictFlag = ThreadLocal.withInitial { false }
    var conflict: Boolean
        get() = conflictFlag.get()
        private set(v) = conflictFlag.set(v)
    const val MAX_SUBTASK_DEPTH = 3

    private val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    // ── Sync operations ──────────────────────────────────────────

    /** Applies a `note` operation (create / update / delete); returns the change and the new version. */
    /**
     * [baseVersion]: the note version the client edited. When the server has a newer one, the
     * edit is not applied and [conflict] is reported (AND-S5: the client asks the user).
     */
    fun applyNoteOp(tx: DSLContext, ownerId: UUID, id: UUID, type: String, fields: Map<String, JsonElement?>, baseVersion: Long? = null): Pair<ChangeLog.Change?, Long?> {
        conflict = false
        val existing = tx.selectFrom(NotesTable.TABLE).where(NotesTable.ID.eq(id)).forUpdate().fetchOne()
        if (existing != null) requireOwnTask(tx, ownerId, existing.get(NotesTable.TASK_ID))
        return when (type) {
            "create", "update" -> {
                val content = TaskFields.str(fields["content"])
                    ?: if (existing == null) throw IllegalArgumentException("fields.content is required") else existing.get(NotesTable.CONTENT)
                require(content.length <= MAX_NOTE_LENGTH) { "content is longer than $MAX_NOTE_LENGTH characters" }
                if (existing == null) {
                    if (type == "update") throw NotFoundException("Note not found")
                    val taskId = uuidField(fields, "taskId", "task_id") ?: throw IllegalArgumentException("fields.taskId is required")
                    requireOwnTask(tx, ownerId, taskId, includeDeleted = false)
                    val rec = tx.insertInto(NotesTable.TABLE)
                        .set(NotesTable.ID, id)
                        .set(NotesTable.TASK_ID, taskId)
                        .set(NotesTable.CONTENT, content)
                        .returning(NotesTable.VERSION)
                        .fetchOne()!!
                    val version = rec.get(NotesTable.VERSION)
                    ChangeLog.append(tx, ownerId, "note", id, "create", notePayload(taskId, content), version) to version
                } else {
                    if (type == "update" && baseVersion != null && existing.get(NotesTable.VERSION) > baseVersion) {
                        conflict = true
                        return null to existing.get(NotesTable.VERSION)
                    }
                    val version = existing.get(NotesTable.VERSION) + 1
                    tx.update(NotesTable.TABLE)
                        .set(NotesTable.CONTENT, content)
                        .set(NotesTable.VERSION, version)
                        .set(NotesTable.UPDATED_AT, OffsetDateTime.now())
                        .where(NotesTable.ID.eq(id))
                        .execute()
                    ChangeLog.append(tx, ownerId, "note", id, "update", notePayload(existing.get(NotesTable.TASK_ID), content), version) to version
                }
            }
            "delete" -> {
                if (existing == null || existing.get(NotesTable.DELETED_AT) != null) return null to null
                val version = existing.get(NotesTable.VERSION) + 1
                tx.update(NotesTable.TABLE)
                    .set(NotesTable.DELETED_AT, OffsetDateTime.now())
                    .set(NotesTable.VERSION, version)
                    .where(NotesTable.ID.eq(id))
                    .execute()
                ChangeLog.append(tx, ownerId, "note", id, "delete", buildJsonObject { put("task_id", existing.get(NotesTable.TASK_ID).toString()) }, version) to version
            }
            else -> throw IllegalArgumentException("Unknown note operation type: $type")
        }
    }

    /** Applies an `attachment` operation (create / delete). Only http(s) links are accepted. */
    fun applyAttachmentOp(tx: DSLContext, ownerId: UUID, id: UUID, type: String, fields: Map<String, JsonElement?>): Pair<ChangeLog.Change?, Long?> {
        val existing = tx.selectFrom(AttachmentsTable.TABLE).where(AttachmentsTable.ID.eq(id)).forUpdate().fetchOne()
        if (existing != null) requireOwnTask(tx, ownerId, existing.get(AttachmentsTable.TASK_ID))
        return when (type) {
            "create" -> {
                if (existing != null) return null to existing.get(AttachmentsTable.VERSION) // idempotent
                val taskId = uuidField(fields, "taskId", "task_id") ?: throw IllegalArgumentException("fields.taskId is required")
                requireOwnTask(tx, ownerId, taskId, includeDeleted = false)
                val url = TaskFields.str(fields["url"])?.trim() ?: throw IllegalArgumentException("fields.url is required")
                requireHttpUrl(url)
                val name = TaskFields.str(fields["name"])?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: url.take(500)
                val version = tx.insertInto(AttachmentsTable.TABLE)
                    .set(AttachmentsTable.ID, id)
                    .set(AttachmentsTable.TASK_ID, taskId)
                    .set(AttachmentsTable.NAME, name)
                    .set(AttachmentsTable.URL, url)
                    .returning(AttachmentsTable.VERSION)
                    .fetchOne()!!.get(AttachmentsTable.VERSION)
                val payload = buildJsonObject {
                    put("task_id", taskId.toString())
                    put("name", name)
                    put("url", url)
                }
                ChangeLog.append(tx, ownerId, "attachment", id, "create", payload, version) to version
            }
            "delete" -> {
                if (existing == null || existing.get(AttachmentsTable.DELETED_AT) != null) return null to null
                val version = existing.get(AttachmentsTable.VERSION) + 1
                tx.update(AttachmentsTable.TABLE)
                    .set(AttachmentsTable.DELETED_AT, OffsetDateTime.now())
                    .set(AttachmentsTable.VERSION, version)
                    .where(AttachmentsTable.ID.eq(id))
                    .execute()
                ChangeLog.append(tx, ownerId, "attachment", id, "delete", buildJsonObject { put("task_id", existing.get(AttachmentsTable.TASK_ID).toString()) }, version) to version
            }
            else -> throw IllegalArgumentException("Unknown attachment operation type: $type")
        }
    }

    /**
     * Subtasks (SPEC FR-5): the parent must be another task of the same user, the tree is at most
     * [MAX_SUBTASK_DEPTH] levels deep and has no cycles. [taskId] is null for a new task.
     */
    fun validateParent(tx: DSLContext, ownerId: UUID, taskId: UUID?, parentId: UUID) {
        if (parentId == taskId) throw IllegalArgumentException("A task cannot be its own parent")
        // Levels above the new position: parent, grandparent, …
        var levelsAbove = 0
        var cursor: UUID? = parentId
        val seen = HashSet<UUID>()
        while (cursor != null) {
            if (cursor == taskId || !seen.add(cursor)) throw IllegalArgumentException("parent_id would create a cycle")
            val row = tx.select(TasksTable.OWNER_ID, TasksTable.PARENT_ID).from(TasksTable.TABLE)
                .where(TasksTable.ID.eq(cursor)).fetchOne()
                ?: throw ForbiddenException("Reference parent_id=$parentId does not belong to the current scope")
            if (row.value1() != ownerId) throw ForbiddenException("Reference parent_id=$parentId does not belong to the current scope")
            levelsAbove++
            cursor = row.value2()
        }
        val levelsBelow = if (taskId == null) 0 else subtreeDepth(tx, taskId)
        if (levelsAbove + 1 + levelsBelow > MAX_SUBTASK_DEPTH) {
            throw IllegalArgumentException("Subtasks are limited to $MAX_SUBTASK_DEPTH levels")
        }
    }

    /** Number of levels below [taskId] (0 for a leaf). */
    private fun subtreeDepth(tx: DSLContext, taskId: UUID): Int {
        var depth = 0
        var level = listOf(taskId)
        while (level.isNotEmpty() && depth <= MAX_SUBTASK_DEPTH) {
            level = tx.select(TasksTable.ID).from(TasksTable.TABLE)
                .where(TasksTable.PARENT_ID.`in`(level), TasksTable.DELETED_AT.isNull)
                .fetch { it.value1() }
            if (level.isNotEmpty()) depth++
        }
        return depth
    }

    /**
     * A repeating task's next instance gets the parent's tags and notes (SPEC F-4). They are copied
     * with change-log entries, so clients see them through the delta like any other change.
     */
    fun copyToNextInstance(tx: DSLContext, ownerId: UUID, fromTask: UUID, toTask: UUID): List<ChangeLog.Change> {
        val changes = mutableListOf<ChangeLog.Change>()
        val tags = tx.select(TagsTable.ID, TagsTable.NAME)
            .from(TaskTagsTable.TABLE).join(TagsTable.TABLE).on(TagsTable.ID.eq(TaskTagsTable.TAG_ID))
            .where(TaskTagsTable.TASK_ID.eq(fromTask))
            .fetch()
        for (tag in tags) {
            tx.insertInto(TaskTagsTable.TABLE)
                .set(TaskTagsTable.TASK_ID, toTask)
                .set(TaskTagsTable.TAG_ID, tag.value1())
                .onConflictDoNothing()
                .execute()
            changes += ChangeLog.append(tx, ownerId, "task", toTask, "tag_add", buildJsonObject { put("tag", tag.value2()) })
        }
        val notes = tx.select(NotesTable.CONTENT).from(NotesTable.TABLE)
            .where(NotesTable.TASK_ID.eq(fromTask), NotesTable.DELETED_AT.isNull)
            .orderBy(NotesTable.CREATED_AT)
            .fetch { it.value1() }
        for (content in notes) {
            val rec = tx.insertInto(NotesTable.TABLE)
                .set(NotesTable.TASK_ID, toTask)
                .set(NotesTable.CONTENT, content)
                .returning(NotesTable.ID, NotesTable.VERSION)
                .fetchOne()!!
            changes += ChangeLog.append(tx, ownerId, "note", rec.get(NotesTable.ID), "create", notePayload(toTask, content), rec.get(NotesTable.VERSION))
        }
        return changes
    }

    /** Note created from a Smart Add "// text" token on the REST create path. */
    fun createNote(tx: DSLContext, ownerId: UUID, taskId: UUID, content: String): ChangeLog.Change {
        val rec = tx.insertInto(NotesTable.TABLE)
            .set(NotesTable.TASK_ID, taskId)
            .set(NotesTable.CONTENT, content)
            .returning(NotesTable.ID, NotesTable.VERSION)
            .fetchOne()!!
        return ChangeLog.append(tx, ownerId, "note", rec.get(NotesTable.ID), "create", notePayload(taskId, content), rec.get(NotesTable.VERSION))
    }

    // ── Reads ────────────────────────────────────────────────────

    /** Notes of a task of [userId] (404 for other users' tasks). */
    fun notesOfTask(userId: String, taskId: String): List<NoteResponse> {
        val id = runCatching { UUID.fromString(taskId) }.getOrElse { throw NotFoundException("Task not found") }
        val ctx = Database.ctx()
        requireOwnTask(ctx, UUID.fromString(userId), id)
        return notesFor(ctx, listOf(id))
    }

    fun notesFor(tx: DSLContext, taskIds: Collection<UUID>): List<NoteResponse> =
        if (taskIds.isEmpty()) emptyList() else
            tx.selectFrom(NotesTable.TABLE)
                .where(NotesTable.TASK_ID.`in`(taskIds), NotesTable.DELETED_AT.isNull)
                .orderBy(NotesTable.CREATED_AT)
                .fetch { noteResponse(it) }

    fun attachmentsFor(tx: DSLContext, taskIds: Collection<UUID>): List<AttachmentResponse> =
        if (taskIds.isEmpty()) emptyList() else
            tx.selectFrom(AttachmentsTable.TABLE)
                .where(AttachmentsTable.TASK_ID.`in`(taskIds), AttachmentsTable.DELETED_AT.isNull)
                .orderBy(AttachmentsTable.CREATED_AT)
                .fetch {
                    AttachmentResponse(
                        id = it.get(AttachmentsTable.ID).toString(),
                        taskId = it.get(AttachmentsTable.TASK_ID).toString(),
                        name = it.get(AttachmentsTable.NAME),
                        url = it.get(AttachmentsTable.URL),
                        version = it.get(AttachmentsTable.VERSION),
                        createdAt = it.get(AttachmentsTable.CREATED_AT).format(fmt),
                    )
                }

    /**
     * Change history of a task card (WEB-F3): the task's own entries plus those of its notes and
     * attachments, newest first. Only the caller's own change log is read.
     */
    fun history(userId: String, taskId: String, limit: Int = 100): List<HistoryEntry> {
        val ownerId = UUID.fromString(userId)
        val id = runCatching { UUID.fromString(taskId) }.getOrElse { throw NotFoundException("Task not found") }
        return Database.ctx().transactionResult { cfg ->
            val tx = org.jooq.impl.DSL.using(cfg)
            requireOwnTask(tx, ownerId, id)
            val childIds = tx.select(NotesTable.ID).from(NotesTable.TABLE).where(NotesTable.TASK_ID.eq(id)).fetch { it.value1() } +
                tx.select(AttachmentsTable.ID).from(AttachmentsTable.TABLE).where(AttachmentsTable.TASK_ID.eq(id)).fetch { it.value1() }
            tx.selectFrom(ChangeLogTable.TABLE)
                .where(ChangeLogTable.SCOPE_ID.eq(ChangeLog.personalScope(ownerId)))
                .and(ChangeLogTable.ENTITY_ID.`in`(childIds + id))
                .orderBy(ChangeLogTable.SEQ.desc())
                .limit(limit.coerceIn(1, 500))
                .fetch {
                    HistoryEntry(
                        seq = it.get(ChangeLogTable.SEQ),
                        entity = it.get(ChangeLogTable.ENTITY),
                        entityId = it.get(ChangeLogTable.ENTITY_ID).toString(),
                        op = it.get(ChangeLogTable.OP),
                        payload = it.get(ChangeLogTable.PAYLOAD)?.data() ?: "{}",
                        ts = it.get(ChangeLogTable.TS).format(fmt),
                    )
                }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun noteResponse(r: Record) = NoteResponse(
        id = r.get(NotesTable.ID).toString(),
        taskId = r.get(NotesTable.TASK_ID).toString(),
        content = r.get(NotesTable.CONTENT),
        version = r.get(NotesTable.VERSION),
        createdAt = r.get(NotesTable.CREATED_AT).format(fmt),
        updatedAt = r.get(NotesTable.UPDATED_AT).format(fmt),
    )

    private fun notePayload(taskId: UUID, content: String): JsonObject = buildJsonObject {
        put("task_id", taskId.toString())
        put("content", content)
    }

    /** The task exists and belongs to [ownerId]; otherwise 404 (no existence leak to other users). */
    private fun requireOwnTask(tx: DSLContext, ownerId: UUID, taskId: UUID, includeDeleted: Boolean = true) {
        var cond = TasksTable.ID.eq(taskId).and(TasksTable.OWNER_ID.eq(ownerId))
        if (!includeDeleted) cond = cond.and(TasksTable.DELETED_AT.isNull)
        if (!tx.fetchExists(tx.selectOne().from(TasksTable.TABLE).where(cond))) throw NotFoundException("Task not found")
    }

    private fun uuidField(fields: Map<String, JsonElement?>, vararg keys: String): UUID? {
        val raw = keys.firstNotNullOfOrNull { TaskFields.str(fields[it]) } ?: return null
        return runCatching { UUID.fromString(raw) }.getOrElse { throw IllegalArgumentException("Invalid UUID: $raw") }
    }

    private fun requireHttpUrl(url: String) {
        require(url.length <= 2048) { "url is too long" }
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()) {
            "url must be an http(s) link"
        }
    }
}
