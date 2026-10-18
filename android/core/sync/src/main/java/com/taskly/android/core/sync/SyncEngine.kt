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
import com.taskly.android.core.database.ListEntity
import com.taskly.android.core.database.SyncOperationEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.ChangeEntryDto
import com.taskly.android.core.network.SyncOpDto
import com.taskly.android.core.network.TasklyApiClient
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
    const val COMPLETE = "complete"
    const val UNCOMPLETE = "uncomplete"
    const val DELETE = "delete"
}

/**
 * Client side of the sync protocol (ARCH §5): local mutations are written to Room together
 * with a queued operation; [push] sends the queue to `/v1/sync` and drops only acknowledged
 * ops; [pull] applies the server change log for the user's scope.
 */
class SyncEngine(
    private val db: TasklyDatabase,
    private val api: TasklyApiClient,
) {
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
            )
        )
    }

    /**
     * Pushes pending operations in order. Acknowledged ops are removed; ops the server rejected
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
            )
        }

        val results = try {
            api.syncPush(ops, accessToken)
        } catch (e: Exception) {
            pending.forEach { db.syncQueueDao().markFailed(it.opId, STATUS_PENDING, e.message ?: e::class.simpleName) }
            return 0
        }

        var acknowledged = 0
        db.withTransaction {
            for (r in results) {
                // "conflict_resolved" = applied, some fields lost to newer server values (SYNC-1)
                if (r.status == "ok" || r.status == "conflict_resolved") {
                    db.syncQueueDao().remove(r.opId)
                    acknowledged++
                } else {
                    db.syncQueueDao().markFailed(r.opId, STATUS_FAILED, r.error ?: "status=${r.status}")
                }
            }
        }
        return acknowledged
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
                for (entry in page) applyChange(userId, entry)
            }
            cursor = page.last().seq
            if (page.size < PAGE_SIZE) break
        }
        return cursor
    }

    /** One full cycle: push local changes, then pull remote ones. */
    suspend fun syncNow(userId: String, accessToken: String, afterSeq: Long): Long {
        push(accessToken)
        return pull(userId, afterSeq, accessToken)
    }

    // ── Applying server changes ──────────────────────────────────

    private suspend fun applyChange(userId: String, entry: ChangeEntryDto) {
        val data = payloadOf(entry.payload)
        val now = entry.ts
        when (entry.entity) {
            "task" -> {
                val existing = db.taskDao().getById(entry.entityId)
                when (entry.op) {
                    "create", "update", "move" -> {
                        val base = existing ?: TaskEntity(
                            id = entry.entityId, ownerId = userId, listId = "",
                            name = "", createdAt = now, updatedAt = now,
                        )
                        db.taskDao().upsert(mergeTask(base, data).copy(updatedAt = now))
                    }
                    "complete" -> existing?.let {
                        db.taskDao().upsert(it.copy(completedAt = data.str("completed_at") ?: now, updatedAt = now))
                    }
                    "uncomplete" -> existing?.let {
                        db.taskDao().upsert(it.copy(completedAt = null, updatedAt = now))
                    }
                    "delete" -> db.taskDao().softDelete(entry.entityId, now, now)
                }
            }
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
        }
    }

    /** Applies only the fields present in a change payload (server keys are snake_case). */
    private fun mergeTask(t: TaskEntity, d: JsonObject): TaskEntity = t.copy(
        name = if (d.has("name")) d.str("name") ?: t.name else t.name,
        listId = if (d.has("list_id", "listId")) d.str("list_id", "listId") ?: t.listId else t.listId,
        priority = if (d.has("priority")) d.int("priority") else t.priority,
        dueAt = if (d.has("due_at", "dueAt")) d.str("due_at", "dueAt") else t.dueAt,
        dueHasTime = if (d.has("due_has_time", "dueHasTime")) d.bool("due_has_time", "dueHasTime") ?: false else t.dueHasTime,
        startAt = if (d.has("start_at", "startAt")) d.str("start_at", "startAt") else t.startAt,
        estimateMin = if (d.has("estimate_min", "estimateMin")) d.int("estimate_min", "estimateMin") else t.estimateMin,
        rrule = if (d.has("rrule")) d.str("rrule") else t.rrule,
        repeatMode = if (d.has("repeat_mode", "repeatMode")) d.str("repeat_mode", "repeatMode") else t.repeatMode,
        completedAt = if (d.has("completed_at", "completedAt")) d.str("completed_at", "completedAt") else t.completedAt,
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
    private fun JsonObject.bool(vararg keys: String): Boolean? = (first(*keys) as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_FAILED = "failed"
        private const val PAGE_SIZE = 100
        private const val PERIODIC_WORK = "taskly_periodic_sync"
        private const val ONE_TIME_WORK = "taskly_sync_now"

        private val json = Json { ignoreUnknownKeys = true }

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Periodic safety-net sync (AND-S4); KEEP makes repeated calls harmless. */
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

/** Background sync. Credentials and cursor come from [SyncSession]. */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val session = SyncSession(applicationContext)
        val token = session.accessToken ?: return Result.success() // signed out: nothing to sync
        val userId = session.userId ?: return Result.success()
        val engine = SyncEngine(TasklyDatabase.getInstance(applicationContext), TasklyApiClient.create(session.baseUrl))
        return try {
            session.cursor = engine.syncNow(userId, token, session.cursor)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
