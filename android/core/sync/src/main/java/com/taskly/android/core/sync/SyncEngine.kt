package com.taskly.android.core.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import com.taskly.android.core.database.ListEntity
import com.taskly.android.core.database.SyncOperationEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.ChangeEntryDto
import com.taskly.android.core.network.SyncOpDto
import com.taskly.android.core.network.TasklyApiClient
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

object SyncEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private fun apiClient(): TasklyApiClient = TasklyApiClient.create()

    /** Same as [processQueue] but with an injectable client (for testing with MockEngine). */
    suspend fun processQueueWithClient(
        db: TasklyDatabase,
        accessToken: String,
        client: TasklyApiClient
    ): Int {
        return processQueueImpl(db, accessToken, client)
    }

    /** Write a local mutation + queue the operation to push to the server. */
    suspend fun enqueueOperation(
        db: TasklyDatabase,
        entityType: String,
        entityId: String,
        operationType: String,
        fields: Map<String, Any?>,
        userId: String
    ) {
        val fieldElements = fields.mapValues { (_, v) ->
            when (v) {
                null -> JsonNull
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }

        val op = SyncOperationEntity(
            opId = UUID.randomUUID().toString(),
            entityType = entityType,
            entityId = entityId,
            operationType = operationType,
            scope = userId,
            fields = JsonObject(fieldElements).toString(),
            createdAt = Clock.System.now().toString(),
            retryCount = 0,
            lastError = null,
            status = "pending"
        )
        db.syncQueueDao().insert(op)
    }

    /** Push all pending ops to the server. Removes acknowledged ops, marks the rest failed. Returns processed count. */
    suspend fun processQueue(db: TasklyDatabase, accessToken: String): Int {
        return processQueueImpl(db, accessToken)
    }

    /** Internal impl with optional client override. */
    private suspend fun processQueueImpl(
        db: TasklyDatabase,
        accessToken: String,
        client: TasklyApiClient = apiClient()
    ): Int {
        val pending = db.syncQueueDao().getPending()
        if (pending.isEmpty()) return 0

        val ops = pending.map { op ->
            SyncOpDto(
                opId = op.opId,
                scope = op.scope,
                entity = op.entityType,
                entityId = op.entityId,
                type = op.operationType,
                fields = runCatching { json.parseToJsonElement(op.fields).jsonObject }.getOrNull()
            )
        }

        var processed = 0
        try {
            val results = client.syncPush(ops, accessToken)
            for (r in results) {
                if (r.status == "ok") {
                    db.syncQueueDao().remove(r.opId)
                    processed++
                } else {
                    db.syncQueueDao().markFailed(r.opId, "failed", r.error ?: "status=${r.status}")
                }
            }
        } catch (e: Exception) {
            // Network / server unavailable: keep the queue as pending, schedule retry.
            pending.forEach { op ->
                db.syncQueueDao().markFailed(
                    op.opId, "pending",
                    e.message ?: "Exception: ${e::class.simpleName}"
                )
            }
        }
        return processed
    }

    /**
     * Pull change-log entries for [scope] since [afterSeq] and apply them locally.
     * Returns the number of applied changes.
     */
    suspend fun pullDeltas(
        db: TasklyDatabase,
        scope: String,
        afterSeq: Long,
        accessToken: String
    ): Int {
        var applied = 0
        var cursor: Long? = afterSeq.takeIf { it > 0 }
        var page: List<ChangeEntryDto>

        do {
            page = try {
                apiClient().syncDelta(scope, cursor, limit = 100, accessToken = accessToken).changes
            } catch (_: Exception) {
                emptyList() // recoverable on next cycle
            }
            if (page.isEmpty()) break

            for (entry in page) {
                applyDelta(db, entry)
                applied++
                cursor = entry.seq
            }
        } while (page.size == 100) // full page → there may be more

        return applied
    }

    /** Apply a single change-log entry to the local Room database. */
    private suspend fun applyDelta(db: TasklyDatabase, entry: ChangeEntryDto) {
        val data = parsePayload(entry.payload) ?: return
        when (entry.entity) {
            "task" -> when (entry.op) {
                "create", "update" -> {
                    val task = parseTaskFromJson(data) ?: return
                    db.taskDao().upsert(task)
                }
                "delete" -> {
                    val deletedAt = data["deletedAt"]?.jsonPrimitive?.contentOrNull
                    val updatedAt = data["updatedAt"]?.jsonPrimitive?.contentOrNull
                    if (deletedAt != null && updatedAt != null) {
                        db.taskDao().softDelete(entry.entityId, deletedAt, updatedAt)
                    }
                }
            }
            "list" -> when (entry.op) {
                "create", "update" -> {
                    val list = parseListFromJson(data) ?: return
                    db.listDao().upsert(list)
                }
                "delete" -> {
                    val deletedAt = data["deletedAt"]?.jsonPrimitive?.contentOrNull
                    val updatedAt = data["updatedAt"]?.jsonPrimitive?.contentOrNull
                    if (deletedAt != null && updatedAt != null) {
                        db.listDao().softDelete(entry.entityId, deletedAt, updatedAt)
                    }
                }
            }
        }
    }

    /** Payload arrives either as a raw JSON object or as a JSON string containing the object. */
    private fun parsePayload(payload: JsonElement?): JsonObject? {
        if (payload == null || payload == JsonNull) return null
        return when (payload) {
            is JsonObject -> payload
            is JsonPrimitive -> runCatching { json.parseToJsonElement(payload.content).jsonObject }.getOrNull()
            else -> null
        }
    }

    /** Schedule periodic sync via WorkManager with exponential backoff. */
    fun enqueuePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "taskly_periodic_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
    }

    /**
     * Idempotent: call once at app startup to schedule periodic sync.
     * Safe to call multiple times — WorkManager KEEP policy prevents duplicates.
     */
    fun initOnAppStart(context: Context) {
        enqueuePeriodicSync(context)
    }

    // ── JSON helpers ─────────────────────────────────────────────

    private fun parseTaskFromJson(data: JsonObject): TaskEntity? = try {
        TaskEntity(
            id = data["id"]?.jsonPrimitive?.content ?: return null,
            ownerId = data["ownerId"]?.jsonPrimitive?.content ?: "",
            listId = data["listId"]?.jsonPrimitive?.content ?: "",
            parentId = data["parentId"]?.jsonPrimitive?.contentOrNull,
            name = data["name"]?.jsonPrimitive?.content ?: "",
            priority = data["priority"]?.jsonPrimitive?.intOrNull,
            dueAt = data["dueAt"]?.jsonPrimitive?.contentOrNull,
            dueHasTime = data["dueHasTime"]?.jsonPrimitive?.boolean ?: false,
            startAt = data["startAt"]?.jsonPrimitive?.contentOrNull,
            estimateMin = data["estimateMin"]?.jsonPrimitive?.intOrNull,
            rrule = data["rrule"]?.jsonPrimitive?.contentOrNull,
            repeatMode = data["repeatMode"]?.jsonPrimitive?.contentOrNull,
            completedAt = data["completedAt"]?.jsonPrimitive?.contentOrNull,
            postponedCount = data["postponedCount"]?.jsonPrimitive?.int ?: 0,
            url = data["url"]?.jsonPrimitive?.contentOrNull,
            version = data["version"]?.jsonPrimitive?.long ?: 1,
            sortOrder = data["sortOrder"]?.jsonPrimitive?.int ?: data["sort_order"]?.jsonPrimitive?.int ?: 0,
            deletedAt = data["deletedAt"]?.jsonPrimitive?.contentOrNull,
            createdAt = data["createdAt"]?.jsonPrimitive?.content ?: "",
            updatedAt = data["updatedAt"]?.jsonPrimitive?.content ?: ""
        )
    } catch (_: Exception) { null }

    private fun parseListFromJson(data: JsonObject): ListEntity? = try {
        ListEntity(
            id = data["id"]?.jsonPrimitive?.content ?: return null,
            ownerId = data["ownerId"]?.jsonPrimitive?.content ?: "",
            name = data["name"]?.jsonPrimitive?.content ?: "",
            isSmart = data["isSmart"]?.jsonPrimitive?.boolean ?: false,
            query = data["query"]?.jsonPrimitive?.contentOrNull,
            color = data["color"]?.jsonPrimitive?.contentOrNull,
            archived = data["archived"]?.jsonPrimitive?.boolean ?: false,
            sortOrder = data["sortOrder"]?.jsonPrimitive?.int ?: 0,
            version = data["version"]?.jsonPrimitive?.long ?: 1,
            deletedAt = data["deletedAt"]?.jsonPrimitive?.contentOrNull,
            createdAt = data["createdAt"]?.jsonPrimitive?.content ?: "",
            updatedAt = data["updatedAt"]?.jsonPrimitive?.content ?: ""
        )
    } catch (_: Exception) { null }
}

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val db = TasklyDatabase.getInstance(applicationContext)
            // Read access token from SharedPreferences (persisted by AuthRepository)
            val prefs = applicationContext.getSharedPreferences("taskly_auth", android.content.Context.MODE_PRIVATE)
            val accessToken = prefs.getString("auth_access_token", null) ?: return Result.retry()
            val processed = SyncEngine.processQueue(db, accessToken)
            if (processed >= 0) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}