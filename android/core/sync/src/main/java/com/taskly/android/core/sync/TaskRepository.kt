package com.taskly.android.core.sync

import androidx.room.withTransaction
import com.taskly.android.core.database.ConflictEntity
import com.taskly.android.core.database.LocationEntity
import com.taskly.android.core.database.NoteEntity
import com.taskly.android.core.database.TagEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TaskTagEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.shared.smartadd.SmartAddParser
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Task mutations (AND-S2): every local write and the sync operation describing it are
 * committed in ONE Room transaction, so the queue can never miss or invent a change.
 * The full-text index is updated in the same transaction (AND-S7).
 */
class TaskRepository(
    private val db: TasklyDatabase,
    private val sync: SyncEngine,
) {
    private fun now(): String = Clock.System.now().toString()

    data class NewTask(
        val name: String,
        val listId: String = INBOX,
        val parentId: String? = null,
        val priority: Int? = null,
        val dueAt: String? = null,
        val dueHasTime: Boolean = false,
        val startAt: String? = null,
        val rrule: String? = null,
        val repeatMode: String? = null,
        val estimateMin: Int? = null,
        val locationId: String? = null,
        val tags: List<String> = emptyList(),
        val note: String? = null,
    )

    suspend fun createTask(userId: String, name: String, listId: String = INBOX): TaskEntity =
        create(userId, NewTask(name = name, listId = listId))

    suspend fun create(userId: String, n: NewTask): TaskEntity {
        val now = now()
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            ownerId = userId,
            listId = n.listId,
            parentId = n.parentId,
            name = n.name,
            priority = n.priority,
            dueAt = n.dueAt,
            dueHasTime = n.dueHasTime,
            startAt = n.startAt,
            rrule = n.rrule,
            repeatMode = if (n.rrule != null) n.repeatMode ?: "every" else null,
            estimateMin = n.estimateMin,
            locationId = n.locationId,
            createdAt = now,
            updatedAt = now,
        )
        db.withTransaction {
            db.taskDao().upsert(task)
            sync.enqueue(
                userId, "task", task.id, TaskOps.CREATE,
                buildJsonObject {
                    put("name", task.name)
                    // The server files unknown/local list ids (like the inbox placeholder) into Inbox
                    put("listId", task.listId)
                    task.parentId?.let { put("parentId", it) }
                    task.priority?.let { put("priority", it) }
                    task.dueAt?.let { put("dueAt", it); put("dueHasTime", task.dueHasTime) }
                    task.startAt?.let { put("startAt", it) }
                    task.rrule?.let { put("rrule", it); put("repeatMode", task.repeatMode) }
                    task.estimateMin?.let { put("estimateMin", it) }
                    task.locationId?.let { put("locationId", it) }
                },
            )
            for (tag in n.tags.distinct()) linkTag(userId, task.id, tag)
            n.note?.takeIf { it.isNotBlank() }?.let { insertNote(userId, task.id, it.trim()) }
            sync.reindex(task.id)
        }
        return task
    }

    /**
     * Smart Add on Android: the shared parser (same rules as web and server, ARCH 3.1).
     * "#Name" files the task into an existing list, otherwise it becomes a tag; "@place" uses
     * or creates a location.
     */
    suspend fun createFromSmartAdd(userId: String, input: String, defaultListId: String = INBOX, zone: TimeZone = TimeZone.currentSystemDefault()): TaskEntity? {
        val lists = db.listDao().allNow(userId).filter { !it.isSmart }
        val tags = db.tagDao().allNow(userId)
        val today = Clock.System.now().toLocalDateTime(zone).date
        val r = SmartAddParser().parse(input, today, zone, lists.map { it.name }.toSet(), tags.map { it.name }.toSet())
        if (r.name.isBlank()) return null
        val tagNames = r.tags.toMutableList()
        var listId = defaultListId
        r.listName?.let { name ->
            val list = lists.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (list != null) listId = list.id else tagNames.add(0, name)
        }
        val locationId = r.location?.let { name -> locationByName(userId, name) }
        return create(
            userId,
            NewTask(
                name = r.name.trim(),
                listId = listId,
                priority = r.priority?.level,
                dueAt = r.due?.instant?.toString(),
                dueHasTime = r.due?.hasTime ?: false,
                startAt = r.start?.instant?.toString(),
                rrule = r.repeat?.rrule,
                repeatMode = r.repeat?.mode?.name?.lowercase(),
                estimateMin = r.estimateMin,
                locationId = locationId,
                tags = tagNames,
                note = r.note,
            ),
        )
    }

    /** Existing location by name, or a new one (synced as its own entity). */
    private suspend fun locationByName(userId: String, name: String): String {
        db.locationDao().allNow(userId).firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.id }
        val loc = LocationEntity(UUID.randomUUID().toString(), userId, name, now())
        db.withTransaction {
            db.locationDao().upsert(loc)
            sync.enqueue(userId, "location", loc.id, "create", buildJsonObject { put("name", name) })
        }
        return loc.id
    }

    suspend fun setCompleted(task: TaskEntity, userId: String, completed: Boolean): TaskEntity {
        val now = now()
        val updated = task.copy(completedAt = if (completed) now else null, updatedAt = now)
        db.withTransaction {
            db.taskDao().upsert(updated)
            if (completed) {
                sync.enqueue(userId, "task", task.id, TaskOps.COMPLETE, buildJsonObject { put("completedAt", now) })
            } else {
                sync.enqueue(userId, "task", task.id, TaskOps.UNCOMPLETE)
            }
        }
        return updated
    }

    /** Field edits; null values clear the field. Keys are the server's wire names. */
    suspend fun update(task: TaskEntity, userId: String, change: TaskEntity.() -> TaskEntity): TaskEntity {
        val updated = task.change().copy(updatedAt = now(), version = task.version + 1)
        val fields = buildJsonObject {
            if (updated.name != task.name) put("name", updated.name)
            if (updated.listId != task.listId) put("listId", updated.listId)
            if (updated.priority != task.priority) put("priority", updated.priority.json())
            if (updated.dueAt != task.dueAt || updated.dueHasTime != task.dueHasTime) {
                put("dueAt", updated.dueAt.json()); put("dueHasTime", updated.dueHasTime)
            }
            if (updated.startAt != task.startAt) put("startAt", updated.startAt.json())
            if (updated.rrule != task.rrule || updated.repeatMode != task.repeatMode) {
                put("rrule", updated.rrule.json()); put("repeatMode", updated.repeatMode.json())
            }
            if (updated.estimateMin != task.estimateMin) put("estimateMin", updated.estimateMin.json())
            if (updated.url != task.url) put("url", updated.url.json())
            if (updated.locationId != task.locationId) put("locationId", updated.locationId.json())
            if (updated.parentId != task.parentId) put("parentId", updated.parentId.json())
            if (updated.postponedCount != task.postponedCount) put("postponedCount", updated.postponedCount)
        }
        if (fields.isEmpty()) return task
        db.withTransaction {
            db.taskDao().upsert(updated)
            val onlyList = fields.keys == setOf("listId")
            sync.enqueue(userId, "task", task.id, if (onlyList) TaskOps.MOVE else TaskOps.UPDATE, fields)
            sync.reindex(task.id)
        }
        return updated
    }

    suspend fun rename(task: TaskEntity, userId: String, name: String): TaskEntity = update(task, userId) { copy(name = name) }

    /** Postpone (SPEC FR-3): due + 1 day (or tomorrow), counter + 1. Snooze 1 h for reminders. */
    suspend fun postpone(task: TaskEntity, userId: String, byHour: Boolean = false): TaskEntity {
        val nowInstant = Clock.System.now()
        val due = task.dueAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val next = when {
            byHour -> maxOf(nowInstant, due ?: nowInstant) + 1.hours
            due != null -> due + 1.days
            else -> nowInstant + 1.days
        }
        return update(task, userId) {
            copy(dueAt = next.toString(), dueHasTime = if (byHour) true else task.dueHasTime, postponedCount = postponedCount + 1)
        }
    }

    suspend fun delete(task: TaskEntity, userId: String) {
        val now = now()
        db.withTransaction {
            db.taskDao().softDelete(task.id, now, now)
            sync.enqueue(userId, "task", task.id, TaskOps.DELETE)
            sync.reindex(task.id)
        }
    }

    /** Undo of a delete (snackbar «Отменить»). */
    suspend fun restore(taskId: String, userId: String) {
        val task = db.taskDao().getById(taskId) ?: return
        db.withTransaction {
            db.taskDao().upsert(task.copy(deletedAt = null, updatedAt = now()))
            sync.enqueue(userId, "task", taskId, TaskOps.RESTORE)
            sync.reindex(taskId)
        }
    }

    suspend fun addTag(task: TaskEntity, userId: String, tag: String) = db.withTransaction { linkTag(userId, task.id, tag) }

    suspend fun removeTag(task: TaskEntity, userId: String, tag: String) {
        val t = db.tagDao().byName(userId, tag) ?: return
        db.withTransaction {
            db.tagDao().unlink(task.id, t.id)
            sync.enqueue(userId, "task", task.id, TaskOps.TAG_REMOVE, buildJsonObject { put("tag", t.name) })
        }
    }

    private suspend fun linkTag(userId: String, taskId: String, raw: String) {
        val name = raw.trim().removePrefix("#")
        if (name.isEmpty()) return
        val now = now()
        val tag = db.tagDao().byName(userId, name) ?: TagEntity(UUID.randomUUID().toString(), userId, name, 1, now, now).also { db.tagDao().upsert(it) }
        db.tagDao().link(TaskTagEntity(taskId, tag.id))
        sync.enqueue(userId, "task", taskId, TaskOps.TAG_ADD, buildJsonObject { put("tag", name) })
    }

    // ── Notes ──

    private suspend fun insertNote(userId: String, taskId: String, content: String): NoteEntity {
        val now = now()
        val note = NoteEntity(UUID.randomUUID().toString(), taskId, content, 1, now, now)
        db.noteDao().upsert(note)
        sync.enqueue(userId, "note", note.id, "create", buildJsonObject { put("taskId", taskId); put("content", content) })
        return note
    }

    suspend fun addNote(userId: String, taskId: String, content: String): NoteEntity = db.withTransaction {
        insertNote(userId, taskId, content).also { sync.reindex(taskId) }
    }

    /** The edit carries the version it was based on: a newer server version becomes a conflict. */
    suspend fun updateNote(userId: String, note: NoteEntity, content: String) {
        if (note.content == content) return
        db.withTransaction {
            db.noteDao().upsert(note.copy(content = content, updatedAt = now()))
            sync.enqueue(userId, "note", note.id, "update", buildJsonObject { put("content", content) }, baseVersion = note.version)
            sync.reindex(note.taskId)
        }
    }

    suspend fun deleteNote(userId: String, note: NoteEntity) {
        db.withTransaction {
            db.noteDao().delete(note.id)
            sync.enqueue(userId, "note", note.id, "delete")
            sync.reindex(note.taskId)
        }
    }

    // ── Conflicts (AND-S5) ──

    /** "Keep mine": re-sends the local value on top of the server's version; otherwise just accepts it. */
    suspend fun resolveConflict(userId: String, c: ConflictEntity, keepMine: Boolean) {
        val mine = c.localValue
        db.withTransaction {
            db.conflictDao().delete(c.entityId, c.field)
            if (!keepMine || mine == null) return@withTransaction
            when (c.entityType to c.field) {
                "task" to "name" -> db.taskDao().getById(c.entityId)?.let { t ->
                    db.taskDao().upsert(t.copy(name = mine, updatedAt = now()))
                    sync.enqueue(userId, "task", t.id, TaskOps.UPDATE, buildJsonObject { put("name", mine) })
                    sync.reindex(t.id)
                }
                "note" to "content" -> db.noteDao().getById(c.entityId)?.let { n ->
                    db.noteDao().upsert(n.copy(content = mine, updatedAt = now()))
                    sync.enqueue(userId, "note", n.id, "update", buildJsonObject { put("content", mine) }, baseVersion = n.version)
                    sync.reindex(n.taskId)
                }
            }
        }
    }

    private fun String?.json() = this?.let { JsonPrimitive(it) } ?: JsonNull
    private fun Int?.json() = this?.let { JsonPrimitive(it) } ?: JsonNull

    @Suppress("unused")
    private fun JsonObject.isEmptyObject() = isEmpty()

    companion object {
        /** Local placeholder until the server's Inbox id arrives with the first pull. */
        const val INBOX = "inbox"
    }
}
