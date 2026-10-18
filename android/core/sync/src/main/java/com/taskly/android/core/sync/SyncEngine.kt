package com.taskly.android.core.sync

import android.content.Context
import androidx.work.*
import com.taskly.android.core.database.ListEntity
import com.taskly.android.core.database.SyncOperationEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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

    private fun getHttpClient(token: String): HttpClient {
        return HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
            defaultRequest {
                url("http://10.0.2.2:8080")
                bearerAuth(token)
                contentType(ContentType.Application.Json)
            }
        }
    }

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

    suspend fun processQueue(db: TasklyDatabase, accessToken: String): Int {
        val pending = db.syncQueueDao().getPending()
        if (pending.isEmpty()) return 0

        val client = getHttpClient(accessToken)
        var processed = 0

        try {
            val opsPayload = JsonArray(
                pending.map { op ->
                    JsonObject(
                        mapOf(
                            "opId" to JsonPrimitive(op.opId),
                            "entityType" to JsonPrimitive(op.entityType),
                            "entityId" to JsonPrimitive(op.entityId),
                            "operationType" to JsonPrimitive(op.operationType),
                            "fields" to json.parseToJsonElement(op.fields)
                        )
                    )
                }
            )

            val response = client.post("/v1/sync/push") {
                setBody(opsPayload.toString())
            }

            if (response.status.isSuccess()) {
                val raw = response.bodyAsText()
                val results = json.parseToJsonElement(raw).jsonArray

                for (element in results) {
                    val obj = element.jsonObject
                    val opId = obj["opId"]?.jsonPrimitive?.content ?: continue
                    val success = obj["success"]?.jsonPrimitive?.boolean ?: false
                    if (success) {
                        db.syncQueueDao().remove(opId)
                        processed++
                    } else {
                        val error = obj["error"]?.jsonPrimitive?.contentOrNull
                        db.syncQueueDao().markFailed(opId, "failed", error ?: "Unknown error")
                    }
                }
            } else {
                pending.forEach { op ->
                    db.syncQueueDao().markFailed(op.opId, "failed", "HTTP ${response.status.value}")
                }
            }
        } catch (e: Exception) {
            pending.forEach { op ->
                db.syncQueueDao().markFailed(op.opId, "failed", e.message ?: "Exception: ${e::class.simpleName}")
            }
        } finally {
            client.close()
        }

        return processed
    }

    suspend fun pullDeltas(
        db: TasklyDatabase,
        scope: String,
        afterSeq: Long,
        accessToken: String
    ): Int {
        val client = getHttpClient(accessToken)
        var applied = 0

        try {
            val response = client.get("/v1/sync/delta") {
                parameter("scope", scope)
                parameter("afterSeq", afterSeq.toString())
            }

            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val root = json.parseToJsonElement(body).jsonObject
                val entries = root["entries"]?.jsonArray ?: return 0
                val nextSeq = root["nextSeq"]?.jsonPrimitive?.long ?: afterSeq
                val hasMore = root["hasMore"]?.jsonPrimitive?.boolean ?: false

                for (entry in entries) {
                    val obj = entry.jsonObject
                    val entityType = obj["entityType"]?.jsonPrimitive?.content ?: continue
                    val entityId = obj["entityId"]?.jsonPrimitive?.content ?: continue
                    val operation = obj["operation"]?.jsonPrimitive?.content ?: continue
                    val data = obj["data"]?.jsonObject ?: continue

                    when (entityType) {
                        "task" -> {
                            when (operation) {
                                "upsert" -> {
                                    val task = parseTaskFromJson(data)
                                    if (task != null) {
                                        db.taskDao().upsert(task)
                                        applied++
                                    }
                                }
                                "delete" -> {
                                    val deletedAt = data["deletedAt"]?.jsonPrimitive?.contentOrNull
                                    val updatedAt = data["updatedAt"]?.jsonPrimitive?.contentOrNull
                                    if (deletedAt != null && updatedAt != null) {
                                        db.taskDao().softDelete(entityId, deletedAt, updatedAt)
                                        applied++
                                    }
                                }
                            }
                        }
                        "list" -> {
                            when (operation) {
                                "upsert" -> {
                                    val list = parseListFromJson(data)
                                    if (list != null) {
                                        db.listDao().upsert(list)
                                        applied++
                                    }
                                }
                            }
                        }
                    }
                }

                if (hasMore) {
                    applied += pullDeltas(db, scope, nextSeq, accessToken)
                }
            }
        } catch (_: Exception) {
            // pull failure is recoverable on next cycle
        } finally {
            client.close()
        }

        return applied
    }

    fun enqueuePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "taskly_periodic_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
    }

    // ------------------------------------------------------------------ JSON helpers
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
            val accessToken = inputData.getString("access_token") ?: return Result.failure()
            val processed = SyncEngine.processQueue(db, accessToken)
            if (processed >= 0) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}