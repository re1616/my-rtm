package com.taskly.android.core.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.taskly.android.core.database.AttachmentEntity
import com.taskly.android.core.database.ConflictEntity
import com.taskly.android.core.database.ListEntity
import com.taskly.android.core.database.LocationEntity
import com.taskly.android.core.database.NoteEntity
import com.taskly.android.core.database.SyncOperationEntity
import com.taskly.android.core.database.TagEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TaskTagEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.ChangeEntryDto
import com.taskly.android.core.network.SyncOpDto
import com.taskly.android.core.network.TaskDto
import com.taskly.android.core.network.TasklyApiClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Sync scopes as the server names them (see server ChangeLog.personalScope). */
object SyncScopes {
    fun personal(userId: String): String = "user:$userId"
}

/** Operation types accepted by the server's /v1/sync endpoint for tasks. */
object TaskOps {
    const val CREATE = "create"
    const val UPDATE = "update"
    const val MOVE = "move"
    const val COMPLETE = "complete"
    const val UNCOMPLETE = "uncomplete"
    const val DELETE = "delete"
    const val RESTORE = "restore"
    const val TAG_ADD = "tag_add"
    const val TAG_REMOVE = "tag_remove"
}

/** Access token for sync, refreshed on 401 (the worker and the UI share [SyncSession]). */
interface TokenProvider {
    fun accessToken(): String?
    /** New access token, or null when the session is gone (signed out). */
    suspend fun refresh(): String?
}

/** What the UI shows while syncing (AND-S6: progress of the first snapshot). */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Snapshot(val loaded: Int, val firstPageDone: Boolean) : SyncStatus
    data object Syncing : SyncStatus
    data object Offline : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/**
 * Client side of the sync protocol (ARCH §5): local mutations are written to Room together
 * with a queued operation; [push] sends the queue to `/v1/sync` and drops only acknowledged
 * ops; [pull] applies the server change log for the user's scope; a device that has never
 * synced first loads the snapshot page by page ([snapshot], AND-S6).
 *
 * Server changes never overwrite an entity that still has unsent local operations: the local
 * edit goes first and the server merges it (shared MergeEngine, SYNC-3).
 */
class SyncEngine(
    private val db: TasklyDatabase,
    private val api: TasklyApiClient,
) {
    // One status per process: the worker's engine and the UI's engine report to the same flow
    private val _status = sharedStatus
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Queues an operation for [entityId]. Must be called inside the same Room transaction as
     * the local write it describes (see [TaskRepository]).
     */
    suspend fun enqueue(
        userId: String,
        entity: String,
        entityId: String,
        type: String,
        fields: JsonObject = JsonObject(emptyMap()),
        baseVersion: Long? = null,
    ) {
        db.syncQueueDao().insert(
            SyncOperationEntity(
                opId = UUID.randomUUID().toString(),
                entityType = entity,
                entityId = entityId,
                operationType = type,
                scope = SyncScopes.personal(userId),
                fields = fields.toString(),
                createdAt = Clock.System.now().toString(),
                retryCount = 0,
                lastError = null,
                status = STATUS_PENDING,
                baseVersion = baseVersion,
            )
        )
    }

    /**
     * Pushes pending operations in order. Acknowledged ops are removed; ops the server refused
     * are marked failed with the server's reason; on a network error the queue stays pending.
     * Returns the number of acknowledged ops.
     */
    suspend fun push(accessToken: String): Int {
        val pending = db.syncQueueDao().getPending()
        if (pending.isEmpty()) return 0

        val ops = pending.map { op ->
            SyncOpDto(
                opId = op.opId,
                scope = op.scope,
                entity = op.entityType,
                entityId = op.entityId,
                type = op.operationType,
                fields = runCatching { json.parseToJsonElement(op.fields).jsonObject }.getOrNull(),
                baseVersion = op.baseVersion,
            )
        }

        val results = try {
            api.syncPush(ops, accessToken)
        } catch (e: ClientRequestException) {
            // 401: the caller refreshes the token and retries; other errors keep the queue
            if (e.response.status == HttpStatusCode.Unauthorized) throw e
            return 0
        } catch (e: Exception) {
            return 0 // offline: everything stays pending
        }
        val byId = pending.associateBy { it.opId }

        var acknowledged = 0
        val conflicts = mutableListOf<SyncOperationEntity>()
        db.withTransaction {
            for (r in results) {
                if (r.status == "ok" || r.status == "conflict_resolved") {
                    db.syncQueueDao().remove(r.opId)
                    acknowledged++
                    // SYNC-1: applied, but the server kept a newer value for some field
                    if (r.status == "conflict_resolved") byId[r.opId]?.let(conflicts::add)
                } else {
                    db.syncQueueDao().markFailed(r.opId, STATUS_FAILED, r.error ?: "status=${r.status}")
                }
            }
        }
        for (op in conflicts) recordConflict(op, accessToken)
        return acknowledged
    }

    /**
     * AND-S5: the server merged with the shared MergeEngine; fields where its newer value won
     * are recorded when the user's value differs — the app asks which one to keep (task name
     * and notes). The local copy takes the server's state meanwhile.
     */
    private suspend fun recordConflict(op: SyncOperationEntity, accessToken: String) {
        val fields = runCatching { json.parseToJsonElement(op.fields).jsonObject }.getOrNull() ?: return
        val now = Clock.System.now().toString()
        when (op.entityType) {
            "task" -> {
                val server = runCatching { api.getTask(op.entityId, accessToken) }.getOrNull() ?: return
                val mine = fields.str("name")
                db.withTransaction {
                    if (mine != null && mine != server.name) {
                        db.conflictDao().upsert(ConflictEntity("task", op.entityId, "name", mine, server.name, now))
                    }
                    val local = db.taskDao().getById(op.entityId)
                    if (local != null && db.syncQueueDao().getByEntity(op.entityId).isEmpty()) {
                        db.taskDao().upsert(fromDto(server, local.ownerId, local))
                        reindex(op.entityId)
                    }
                }
            }
            "note" -> {
                val local = db.noteDao().getById(op.entityId) ?: return
                val server = runCatching { api.taskNotes(local.taskId, accessToken) }.getOrNull()?.firstOrNull { it.id == op.entityId } ?: return
                val mine = fields.str("content")
                db.withTransaction {
                    if (mine != null && mine != server.content) {
                        db.conflictDao().upsert(ConflictEntity("note", op.entityId, "content", mine, server.content, now))
                    }
                    db.noteDao().upsert(local.copy(content = server.content, version = server.version, updatedAt = server.updatedAt))
                    reindex(local.taskId)
                }
            }
        }
    }

    /**
     * Pulls the change log of the user's personal scope after [afterSeq] and applies it.
     * Returns the cursor to pass next time (unchanged when there was nothing new).
     */
    suspend fun pull(userId: String, afterSeq: Long, accessToken: String): Long {
        val scope = SyncScopes.personal(userId)
        var cursor = afterSeq
        while (true) {
            val page = api.syncDelta(scope, cursor.takeIf { it > 0 }, PAGE_SIZE, accessToken).changes
            if (page.isEmpty()) break
            db.withTransaction {
                val queued = db.syncQueueDao().queuedEntityIds().toSet()
                for (entry in page) {
                    // Unsent local edits win until they reach the server
                    if (entry.entityId in queued) continue
                    applyChange(userId, entry)
                }
            }
            cursor = page.last().seq
            if (page.size < PAGE_SIZE) break
        }
        return cursor
    }

    /**
     * First sync on this device (AND-S6): loads every live task with its lists, tags, places,
     * notes and attachments page by page. [onPage] reports progress; the app is usable after
     * the first page. Returns the change-log position to continue from.
     */
    suspend fun snapshot(userId: String, accessToken: String, onPage: (loaded: Int) -> Unit = {}): Long {
        var cursor: String? = null
        var seq: Long? = null
        var loaded = 0
        do {
            val page = api.snapshot(cursor, SNAPSHOT_PAGE, accessToken)
            if (seq == null) seq = page.seq ?: 0
            db.withTransaction {
                val queued = db.syncQueueDao().queuedEntityIds().toSet()
                val now = Clock.System.now().toString()
                for (t in page.tags) if (t.id !in queued) db.tagDao().upsert(TagEntity(t.id, userId, t.name, t.version, now, now))
                for (l in page.locations) if (l.id !in queued) db.locationDao().upsert(LocationEntity(l.id, userId, l.name, now))
                for (l in page.lists) if (l.id !in queued) {
                    db.listDao().upsert(
                        ListEntity(l.id, userId, l.name, l.isSmart, l.query, l.color, false, l.sortOrder, l.version, null, l.createdAt, now),
                    )
                }
                for (t in page.tasks) if (t.id !in queued) {
                    db.taskDao().upsert(fromDto(t, userId, db.taskDao().getById(t.id)))
                    for (tagId in t.tags.orEmpty()) db.tagDao().link(TaskTagEntity(t.id, tagId))
                }
                for (n in page.notes) if (n.id !in queued) db.noteDao().upsert(NoteEntity(n.id, n.taskId, n.content, n.version, n.createdAt, n.updatedAt))
                for (a in page.attachments) if (a.id !in queued) db.attachmentDao().upsert(AttachmentEntity(a.id, a.taskId, a.name, a.url, a.createdAt))
                for (t in page.tasks) reindex(t.id)
            }
            loaded += page.tasks.size
            onPage(loaded)
            _status.value = SyncStatus.Snapshot(loaded, firstPageDone = true)
            cursor = page.nextCursor
        } while (cursor != null)
        return seq ?: 0
    }

    /**
     * One full cycle with token refresh: push local changes, then a snapshot (first time) or the
     * delta. Returns the new cursor; throws on network errors (the worker retries).
     */
    suspend fun syncNow(userId: String, tokens: TokenProvider, afterSeq: Long, onCursor: (Long) -> Unit = {}): Long {
        _status.value = SyncStatus.Syncing
        try {
            val cursor = withToken(tokens) { token ->
                push(token)
                var c = afterSeq
                if (c == 0L) {
                    c = snapshot(userId, token)
                    onCursor(c) // the app is ready; a crash later does not repeat the snapshot
                }
                pull(userId, c, token)
            }
            _status.value = SyncStatus.Idle
            return cursor
        } catch (e: java.io.IOException) {
            _status.value = SyncStatus.Offline
            throw e
        } catch (e: Exception) {
            _status.value = SyncStatus.Error(e.message ?: e::class.simpleName.orEmpty())
            throw e
        }
    }

    /** Backwards-compatible single-token cycle (tests, real-server E2E). */
    suspend fun syncNow(userId: String, accessToken: String, afterSeq: Long): Long =
        syncNow(userId, StaticToken(accessToken), afterSeq)

    private suspend fun <T> withToken(tokens: TokenProvider, block: suspend (String) -> T): T {
        val token = tokens.accessToken() ?: throw IllegalStateException("Not signed in")
        return try {
            block(token)
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.Unauthorized) throw e
            val fresh = tokens.refresh() ?: throw e
            block(fresh)
        }
    }

    /** Rebuilds the full-text entry of a task: its name and the text of its notes (AND-S7). */
    suspend fun reindex(taskId: String) {
        db.searchDao().remove(taskId)
        val task = db.taskDao().getById(taskId) ?: return
        if (task.deletedAt != null) return
        val notes = db.noteDao().forTaskNow(taskId).joinToString(" ") { it.content }
        db.searchDao().insert(taskId, listOf(task.name, notes).filter { it.isNotBlank() }.joinToString(" "))
    }

    // ── Applying server changes ──────────────────────────────────

    private suspend fun applyChange(userId: String, entry: ChangeEntryDto) {
        val data = payloadOf(entry.payload)
        val now = entry.ts
        when (entry.entity) {
            "task" -> applyTask(userId, entry, data, now)
            "list" -> {
                val existing = db.listDao().getById(entry.entityId)
                when (entry.op) {
                    "create", "update" -> {
                        val base = existing ?: ListEntity(
                            id = entry.entityId, ownerId = userId, name = "",
                            createdAt = now, updatedAt = now,
                        )
                        db.listDao().upsert(mergeList(base, data).copy(updatedAt = now))
                    }
                    "delete" -> db.listDao().softDelete(entry.entityId, now, now)
                }
            }
            "tag" -> when (entry.op) {
                "create", "update" -> {
                    val existing = db.tagDao().getById(entry.entityId)
                    val name = data.str("name") ?: existing?.name ?: return
                    db.tagDao().upsert(TagEntity(entry.entityId, userId, name, existing?.version ?: 1, existing?.createdAt ?: now, now))
                }
                "delete" -> db.tagDao().delete(entry.entityId)
            }
            "location" -> when (entry.op) {
                "create", "update" -> {
                    val name = data.str("name") ?: return
                    db.locationDao().upsert(LocationEntity(entry.entityId, userId, name, now))
                }
                "delete" -> db.locationDao().delete(entry.entityId)
            }
            "note" -> {
                if (entry.op == "delete") {
                    val taskId = db.noteDao().getById(entry.entityId)?.taskId
                    db.noteDao().delete(entry.entityId)
                    taskId?.let { reindex(it) }
                } else {
                    val existing = db.noteDao().getById(entry.entityId)
                    val taskId = data.str("task_id") ?: existing?.taskId ?: return
                    db.noteDao().upsert(
                        NoteEntity(
                            id = entry.entityId,
                            taskId = taskId,
                            content = data.str("content") ?: existing?.content.orEmpty(),
                            version = (existing?.version ?: 0) + 1,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        ),
                    )
                    reindex(taskId)
                }
            }
            "attachment" -> {
                if (entry.op == "delete") db.attachmentDao().delete(entry.entityId)
                else {
                    val taskId = data.str("task_id") ?: return
                    val url = data.str("url") ?: return
                    db.attachmentDao().upsert(AttachmentEntity(entry.entityId, taskId, data.str("name") ?: url, url, now))
                }
            }
            // unknown entity: newer server, skip (forward compatible)
        }
    }

    private suspend fun applyTask(userId: String, entry: ChangeEntryDto, data: JsonObject, now: String) {
        val existing = db.taskDao().getById(entry.entityId)
        when (entry.op) {
            "create", "update", "move", "restore" -> {
                val base = existing ?: TaskEntity(
                    id = entry.entityId, ownerId = userId, listId = "",
                    name = "", createdAt = now, updatedAt = now,
                )
                var merged = mergeTask(base, data).copy(updatedAt = now)
                if (entry.op == "restore") merged = merged.copy(deletedAt = null)
                db.taskDao().upsert(merged)
            }
            "complete" -> existing?.let {
                db.taskDao().upsert(it.copy(completedAt = data.str("completed_at") ?: now, updatedAt = now))
            }
            "uncomplete" -> existing?.let {
                db.taskDao().upsert(it.copy(completedAt = null, updatedAt = now))
            }
            "delete" -> db.taskDao().softDelete(entry.entityId, now, now)
            "tag_add", "tag_remove" -> {
                val name = data.str("tag") ?: return
                val tag = db.tagDao().byName(userId, name)
                    ?: if (entry.op == "tag_add") TagEntity(UUID.randomUUID().toString(), userId, name, 1, now, now).also { db.tagDao().upsert(it) } else return
                if (entry.op == "tag_add") db.tagDao().link(TaskTagEntity(entry.entityId, tag.id))
                else db.tagDao().unlink(entry.entityId, tag.id)
            }
        }
        reindex(entry.entityId)
    }

    /** Server task (REST/snapshot shape) → local row; keeps local-only columns. */
    private fun fromDto(t: TaskDto, userId: String, local: TaskEntity?): TaskEntity = TaskEntity(
        id = t.id,
        ownerId = local?.ownerId ?: userId,
        listId = t.listId,
        parentId = t.parentId,
        name = t.name,
        priority = t.priority,
        dueAt = t.dueAt,
        dueHasTime = t.dueHasTime,
        startAt = t.startAt,
        estimateMin = t.estimateMin,
        rrule = t.rrule,
        repeatMode = t.repeatMode,
        completedAt = t.completedAt,
        postponedCount = t.postponedCount,
        url = t.url,
        locationId = t.locationId,
        version = t.version,
        sortOrder = local?.sortOrder ?: 0,
        deletedAt = null,
        createdAt = t.createdAt,
        updatedAt = t.updatedAt,
    )

    /** Applies only the fields present in a change payload (server keys are snake_case). */
    private fun mergeTask(t: TaskEntity, d: JsonObject): TaskEntity = t.copy(
        name = if (d.has("name")) d.str("name") ?: t.name else t.name,
        listId = if (d.has("list_id", "listId")) d.str("list_id", "listId") ?: t.listId else t.listId,
        parentId = if (d.has("parent_id", "parentId")) d.str("parent_id", "parentId") else t.parentId,
        locationId = if (d.has("location_id", "locationId")) d.str("location_id", "locationId") else t.locationId,
        priority = if (d.has("priority")) d.int("priority") else t.priority,
        dueAt = if (d.has("due_at", "dueAt")) d.str("due_at", "dueAt") else t.dueAt,
        dueHasTime = if (d.has("due_has_time", "dueHasTime")) d.bool("due_has_time", "dueHasTime") ?: false else t.dueHasTime,
        startAt = if (d.has("start_at", "startAt")) d.str("start_at", "startAt") else t.startAt,
        estimateMin = if (d.has("estimate_min", "estimateMin")) d.int("estimate_min", "estimateMin") else t.estimateMin,
        rrule = if (d.has("rrule")) d.str("rrule") else t.rrule,
        repeatMode = if (d.has("repeat_mode", "repeatMode")) d.str("repeat_mode", "repeatMode") else t.repeatMode,
        completedAt = if (d.has("completed_at", "completedAt")) d.str("completed_at", "completedAt") else t.completedAt,
        postponedCount = if (d.has("postponed_count", "postponedCount")) d.int("postponed_count", "postponedCount") ?: 0 else t.postponedCount,
        url = if (d.has("url")) d.str("url") else t.url,
    )

    private fun mergeList(l: ListEntity, d: JsonObject): ListEntity = l.copy(
        name = if (d.has("name")) d.str("name") ?: l.name else l.name,
        isSmart = if (d.has("is_smart", "isSmart")) d.bool("is_smart", "isSmart") ?: false else l.isSmart,
        query = if (d.has("query")) d.str("query") else l.query,
        color = if (d.has("color")) d.str("color") else l.color,
        archived = if (d.has("archived")) d.bool("archived") ?: false else l.archived,
        sortOrder = if (d.has("sort_order", "sortOrder")) d.int("sort_order", "sortOrder") ?: 0 else l.sortOrder,
    )

    /** The server sends the payload as a JSON string; older entries may carry an object. */
    private fun payloadOf(payload: JsonElement?): JsonObject = when (payload) {
        is JsonObject -> payload
        is JsonPrimitive -> payload.contentOrNull
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        else -> JsonObject(emptyMap())
    }

    private fun JsonObject.has(vararg keys: String) = keys.any { containsKey(it) }
    private fun JsonObject.first(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }
    private fun JsonObject.str(vararg keys: String): String? = (first(*keys) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(vararg keys: String): Int? = (first(*keys) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(vararg keys: String): Boolean? =
        (first(*keys) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_FAILED = "failed"
        private const val PAGE_SIZE = 500
        private const val SNAPSHOT_PAGE = 1000
        private const val PERIODIC_WORK = "taskly_periodic_sync"
        private const val ONE_TIME_WORK = "taskly_sync_now"

        private val json = Json { ignoreUnknownKeys = true }

        private val sharedStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

        /** Sync status of this process (snapshot progress, offline, errors) for the UI. */
        val processStatus: StateFlow<SyncStatus> = sharedStatus.asStateFlow()

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Periodic safety-net sync (AND-S4): every 6 h; KEEP makes repeated calls harmless. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Push as soon as the device is online (AND-S3): runs now, or when the network returns. */
        fun requestSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

private class StaticToken(private val token: String) : TokenProvider {
    override fun accessToken() = token
    override suspend fun refresh(): String? = null
}

/** Tokens from [SyncSession], refreshed with the API (shared by the worker and the UI). */
class SessionTokens(private val session: SyncSession, private val api: TasklyApiClient) : TokenProvider {
    override fun accessToken(): String? = session.accessToken
    override suspend fun refresh(): String? {
        val refresh = session.refreshToken ?: return null
        return try {
            val r = api.refreshToken(refresh)
            session.signIn(r.accessToken, r.refreshToken, r.userId)
            r.accessToken
        } catch (e: ClientRequestException) {
            // The refresh token is no longer valid: sign out, local data stays
            session.signOut()
            null
        }
    }
}

/** Background sync. Credentials and cursor come from [SyncSession]. */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val session = SyncSession(applicationContext)
        val userId = session.userId ?: return Result.success() // signed out: nothing to sync
        val api = TasklyApiClient.create(session.baseUrl)
        val engine = SyncEngine(TasklyDatabase.getInstance(applicationContext), api)
        return try {
            val tokens = SessionTokens(session, api)
            session.cursor = engine.syncNow(userId, tokens, session.cursor) { session.cursor = it }
            // Reminder defaults may have changed on another device; alarms follow the new data
            tokens.accessToken()?.let { token ->
                runCatching { api.accountSettings(token) }.getOrNull()?.let {
                    com.taskly.android.core.sync.reminders.ReminderSettingsStore(applicationContext).settings = it
                }
            }
            com.taskly.android.core.sync.reminders.ReminderScheduler.reschedule(applicationContext)
            SyncHooks.afterSync?.invoke(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Lets the app react to a finished background sync (reschedule reminders, M5). */
object SyncHooks {
    @Volatile
    var afterSync: ((Context) -> Unit)? = null
}
