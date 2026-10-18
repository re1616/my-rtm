package com.taskly.server.sync

import com.taskly.server.db.*
import com.taskly.server.dto.*
import com.taskly.server.redis.RedisService
import org.jooq.impl.DSL
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DefaultConfiguration
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Sync service for offline-first operations.
 * Processes client operations with idempotency, writes to change_log,
 * and fans out via Redis pub/sub.
 *
 * SYNC-1: each operation is wrapped in a database savepoint so that
 * a failed operation does not roll back earlier successful operations.
 */
object SyncService {
    private val logger = LoggerFactory.getLogger("SyncService")
    private val json = Json { encodeDefaults = false }

    /**
     * Process a batch of operations from a client.
     * Each operation is checked for idempotency via processed_ops.
     * Each operation runs in its own savepoint for isolation (SYNC-1).
     */
    fun processOperations(userId: String, ops: List<SyncOperation>): SyncResponse {
        val ctx = Database.ctx()
        val userIdUuid = UUID.fromString(userId)
        val results = mutableListOf<SyncResult>()

        // SEC-3: server-computed scope — ignore client scope
        val serverScope = "user:$userId"

        // Process operations in order within a transaction
        ctx.transaction { config ->
            val tx = DSL.using(config)
            var opIndex = 0

            for (op in ops) {
                val savepointName = "sp_$opIndex"

                try {
                    // SYNC-1: create savepoint before each op
                    tx.execute("SAVEPOINT $savepointName")

                    // Idempotency check
                    val existingResult = tx.selectFrom(ProcessedOpsTable.TABLE)
                        .where(ProcessedOpsTable.OP_ID.eq(op.opId))
                        .and(ProcessedOpsTable.USER_ID.eq(userIdUuid))
                        .fetchOne()

                    if (existingResult != null) {
                        // Return previous result
                        val resultJson = existingResult.get(ProcessedOpsTable.RESULT)
                        val resultData = resultJson.data()
                        results.add(
                            SyncResult(
                                opId = op.opId,
                                status = "ok",
                                entityId = op.entityId,
                                version = null // replayed from cache, no new version
                            )
                        )
                        // Release savepoint for idempotent ops too
                        tx.execute("RELEASE SAVEPOINT $savepointName")
                        continue
                    }

                    // Apply the operation
                    val result = applyOperation(tx, userIdUuid, op)

                    // Write to change_log
                    val seq = tx.insertInto(ChangeLogTable.TABLE)
                        .set(ChangeLogTable.SCOPE_ID, serverScope)
                        .set(ChangeLogTable.ENTITY, op.entity)
                        .set(ChangeLogTable.ENTITY_ID, UUID.fromString(result.entityId ?: op.entityId))
                        .set(ChangeLogTable.OP, op.type)
                        .set(ChangeLogTable.PAYLOAD, JSONB.jsonb(json.encodeToString(op.fields ?: emptyMap())))
                        .set(ChangeLogTable.ACTOR_ID, userIdUuid)
                        .returning(ChangeLogTable.SEQ)
                        .fetchOne()!!
                        .get(ChangeLogTable.SEQ)

                    // Record in processed_ops
                    tx.insertInto(ProcessedOpsTable.TABLE)
                        .set(ProcessedOpsTable.OP_ID, op.opId)
                        .set(ProcessedOpsTable.USER_ID, userIdUuid)
                        .set(ProcessedOpsTable.RESULT, JSONB.jsonb("""{"entityId":"${result.entityId}","version":${result.version}}"""))
                        .execute()

                    results.add(result)

                    // SYNC-1: release savepoint after successful op
                    tx.execute("RELEASE SAVEPOINT $savepointName")

                    // Publish to Redis for WebSocket fanout
                    try {
                        RedisService.publish(
                            "sync:$userId:$serverScope",
                            """{"seq":$seq,"op":"${op.type}","entity":"${op.entity}","entityId":"${result.entityId}","version":${result.version}}"""
                        )
                    } catch (_: Exception) {
                        // Non-critical: WS clients will catch up on next poll
                    }
                } catch (e: Exception) {
                    // SYNC-1: roll back only this savepoint, preserving earlier ops
                    tx.execute("ROLLBACK TO SAVEPOINT $savepointName")

                    logger.warn("Sync operation failed: opId={}, entity={}, error={}", op.opId, op.entity, e.message)
                    results.add(
                        SyncResult(
                            opId = op.opId,
                            status = "error",
                            entityId = op.entityId,
                            error = e.message ?: "Unknown error"
                        )
                    )
                }

                opIndex++
            }
        }

        return SyncResponse(results = results)
    }

    /**
     * Get changes from the change_log since a given sequence number.
     */
    fun getChanges(
        userId: String,
        scopeId: String,
        afterSeq: Long? = null,
        limit: Int = 100
    ): Pair<List<SyncChangeEntry>, Long?> {
        val ctx = Database.ctx()

        val records = ctx.selectFrom(ChangeLogTable.TABLE)
            .where(ChangeLogTable.SCOPE_ID.eq(scopeId))
            .run {
                var q = this
                if (afterSeq != null) {
                    q = q.and(ChangeLogTable.SEQ.gt(afterSeq))
                }
                q.orderBy(ChangeLogTable.SEQ.asc())
                    .limit(limit.coerceIn(1, 500))
            }
            .fetch()
        val lastSeq = if (records.isNotEmpty()) records.last().get(ChangeLogTable.SEQ) else null

        val entries = records.map { rec ->
            SyncChangeEntry(
                seq = rec.get(ChangeLogTable.SEQ),
                scopeId = rec.get(ChangeLogTable.SCOPE_ID),
                entity = rec.get(ChangeLogTable.ENTITY),
                entityId = rec.get(ChangeLogTable.ENTITY_ID).toString(),
                op = rec.get(ChangeLogTable.OP),
                payload = rec.get(ChangeLogTable.PAYLOAD).data(),
                actorId = rec.get(ChangeLogTable.ACTOR_ID).toString(),
                ts = rec.get(ChangeLogTable.TS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            )
        }

        return Pair(entries, lastSeq)
    }

    // ============================================================
    // Private helpers
    // ============================================================

    @kotlinx.serialization.Serializable
    data class SyncChangeEntry(
        val seq: Long,
        val scopeId: String,
        val entity: String,
        val entityId: String,
        val op: String,
        val payload: String? = null,
        val actorId: String,
        val ts: String
    )

    private fun applyOperation(
        tx: org.jooq.DSLContext,
        userId: UUID,
        op: SyncOperation
    ): SyncResult {
        return when (op.entity) {
            "task" -> applyTaskOp(tx, userId, op)
            "list" -> applyListOp(tx, userId, op)
            "tag" -> applyTagOp(tx, userId, op)
            else -> throw IllegalArgumentException("Unknown entity: ${op.entity}")
        }
    }

    private fun applyTaskOp(
        tx: org.jooq.DSLContext,
        userId: UUID,
        op: SyncOperation
    ): SyncResult {
        val entityId = UUID.fromString(op.entityId)
        val now = OffsetDateTime.now()

        return when (op.type) {
            "create" -> {
                var listId = op.fields?.get("listId")?.let { kv ->
                    UUID.fromString(kv.toString().removeSurrounding("\""))
                }

                // SEC-1: reject foreign list_id — only accept lists owned by the user
                if (listId != null) {
                    val listExists = tx.selectOne()
                        .from(ListsTable.TABLE)
                        .where(ListsTable.ID.eq(listId))
                        .and(ListsTable.OWNER_ID.eq(userId))
                        .fetchOne() != null
                    if (!listExists) listId = null
                }

                if (listId == null) {
                    listId = tx.select(ListsTable.ID)
                        .from(ListsTable.TABLE)
                        .where(ListsTable.OWNER_ID.eq(userId))
                        .and(ListsTable.NAME.eq("Inbox"))
                        .and(ListsTable.DELETED_AT.isNull)
                        .fetchOneInto(UUID::class.java)
                        ?: tx.insertInto(ListsTable.TABLE)
                            .set(ListsTable.OWNER_ID, userId)
                            .set(ListsTable.NAME, "Inbox")
                            .returning(ListsTable.ID)
                            .fetchOne()!!
                            .get(ListsTable.ID)
                }

                val record = tx.insertInto(TasksTable.TABLE)
                    .set(TasksTable.ID, entityId)
                    .set(TasksTable.OWNER_ID, userId)
                    .set(TasksTable.LIST_ID, listId)
                    .set(TasksTable.NAME, op.fields?.get("name")?.toString()?.removeSurrounding("\"") ?: "New Task")
                    .onConflict(TasksTable.ID)
                    .doUpdate()
                    .set(TasksTable.OWNER_ID, userId)
                    .set(TasksTable.LIST_ID, listId)
                    .set(TasksTable.NAME, op.fields?.get("name")?.toString()?.removeSurrounding("\"") ?: "New Task")
                    .returning(TasksTable.ID, TasksTable.VERSION)
                    .fetchOne()!!

                SyncResult(
                    opId = op.opId,
                    status = "ok",
                    entityId = record.get(TasksTable.ID).toString(),
                    version = record.get(TasksTable.VERSION)
                )
            }
            "update" -> {
                val update = tx.update(TasksTable.TABLE)
                    .set(TasksTable.UPDATED_AT, now)

                op.fields?.forEach { (key, jsonElement) ->
                    val value = jsonElement?.toString()?.removeSurrounding("\"")
                    val field = when (key) {
                        "name" -> TasksTable.NAME
                        "listId" -> { update.set(TasksTable.LIST_ID, UUID.fromString(value)); return@forEach }
                        "priority" -> { update.set(TasksTable.PRIORITY, value?.toIntOrNull()); return@forEach }
                        "dueAt" -> { update.set(TasksTable.DUE_AT, if (value != null) OffsetDateTime.parse(value) else null); return@forEach }
                        "dueHasTime" -> { update.set(TasksTable.DUE_HAS_TIME, value?.toBoolean() ?: false); return@forEach }
                        "completedAt" -> { update.set(TasksTable.COMPLETED_AT, if (value != null) OffsetDateTime.parse(value) else null); return@forEach }
                        "url" -> TasksTable.URL
                        "rrule" -> TasksTable.RRULE
                        "estimateMin" -> { update.set(TasksTable.ESTIMATE_MIN, value?.toIntOrNull()); return@forEach }
                        "startAt" -> { update.set(TasksTable.START_AT, if (value != null) OffsetDateTime.parse(value) else null); return@forEach }
                        else -> return@forEach
                    }
                    update.set(field, value)
                }

                update
                    .where(TasksTable.ID.eq(entityId))
                    .and(TasksTable.OWNER_ID.eq(userId))
                    .execute()

                val record = tx.selectFrom(TasksTable.TABLE)
                    .where(TasksTable.ID.eq(entityId))
                    .fetchOne()!!

                SyncResult(
                    opId = op.opId,
                    status = "ok",
                    entityId = record.get(TasksTable.ID).toString(),
                    version = record.get(TasksTable.VERSION)
                )
            }
            "delete" -> {
                tx.update(TasksTable.TABLE)
                    .set(TasksTable.DELETED_AT, now)
                    .set(TasksTable.UPDATED_AT, now)
                    .where(TasksTable.ID.eq(entityId))
                    .and(TasksTable.OWNER_ID.eq(userId))
                    .execute()

                SyncResult(opId = op.opId, status = "ok", entityId = op.entityId, version = 0)
            }
            "complete" -> {
                tx.update(TasksTable.TABLE)
                    .set(TasksTable.COMPLETED_AT, now)
                    .set(TasksTable.UPDATED_AT, now)
                    .where(TasksTable.ID.eq(entityId))
                    .and(TasksTable.OWNER_ID.eq(userId))
                    .execute()

                SyncResult(opId = op.opId, status = "ok", entityId = op.entityId, version = 1)
            }
            else -> throw IllegalArgumentException("Unknown task operation type: ${op.type}")
        }
    }

    private fun applyListOp(
        tx: org.jooq.DSLContext,
        userId: UUID,
        op: SyncOperation
    ): SyncResult {
        val entityId = UUID.fromString(op.entityId)
        val now = OffsetDateTime.now()

        return when (op.type) {
            "create" -> {
                val record = tx.insertInto(ListsTable.TABLE)
                    .set(ListsTable.ID, entityId)
                    .set(ListsTable.OWNER_ID, userId)
                    .set(ListsTable.NAME, op.fields?.get("name")?.toString()?.removeSurrounding("\"") ?: "New List")
                    .onConflict(ListsTable.ID)
                    .doUpdate()
                    .set(ListsTable.NAME, op.fields?.get("name")?.toString()?.removeSurrounding("\"") ?: "New List")
                    .returning(ListsTable.ID, ListsTable.VERSION)
                    .fetchOne()!!

                SyncResult(
                    opId = op.opId,
                    status = "ok",
                    entityId = record.get(ListsTable.ID).toString(),
                    version = record.get(ListsTable.VERSION)
                )
            }
            "update" -> {
                val update = tx.update(ListsTable.TABLE)
                    .set(ListsTable.UPDATED_AT, now)

                op.fields?.forEach { (key, jsonElement) ->
                    val value = jsonElement?.toString()?.removeSurrounding("\"")
                    when (key) {
                        "name" -> update.set(ListsTable.NAME, value)
                        "color" -> update.set(ListsTable.COLOR, value)
                        "sortOrder" -> update.set(ListsTable.SORT_ORDER, value?.toIntOrNull())
                        "archived" -> update.set(ListsTable.ARCHIVED, value?.toBoolean() ?: false)
                        "query" -> update.set(ListsTable.QUERY, value)
                        "isSmart" -> update.set(ListsTable.IS_SMART, value?.toBoolean() ?: false)
                    }
                }

                update
                    .where(ListsTable.ID.eq(entityId))
                    .and(ListsTable.OWNER_ID.eq(userId))
                    .execute()

                SyncResult(
                    opId = op.opId,
                    status = "ok",
                    entityId = op.entityId,
                    version = 1
                )
            }
            "delete" -> {
                tx.update(ListsTable.TABLE)
                    .set(ListsTable.DELETED_AT, now)
                    .where(ListsTable.ID.eq(entityId))
                    .and(ListsTable.OWNER_ID.eq(userId))
                    .execute()

                SyncResult(opId = op.opId, status = "ok", entityId = op.entityId, version = 0)
            }
            else -> throw IllegalArgumentException("Unknown list operation type: ${op.type}")
        }
    }

    private fun applyTagOp(
        tx: org.jooq.DSLContext,
        userId: UUID,
        op: SyncOperation
    ): SyncResult {
        val entityId = UUID.fromString(op.entityId)

        return when (op.type) {
            "create" -> {
                val record = tx.insertInto(TagsTable.TABLE)
                    .set(TagsTable.ID, entityId)
                    .set(TagsTable.OWNER_ID, userId)
                    .set(TagsTable.NAME, op.fields?.get("name")?.toString()?.removeSurrounding("\"") ?: "New Tag")
                    .onConflict(TagsTable.ID)
                    .doUpdate()
                    .set(TagsTable.NAME, op.fields?.get("name")?.toString()?.removeSurrounding("\"") ?: "New Tag")
                    .returning(TagsTable.ID, TagsTable.VERSION)
                    .fetchOne()!!

                SyncResult(
                    opId = op.opId,
                    status = "ok",
                    entityId = record.get(TagsTable.ID).toString(),
                    version = record.get(TagsTable.VERSION)
                )
            }
            "delete" -> {
                tx.deleteFrom(TagsTable.TABLE)
                    .where(TagsTable.ID.eq(entityId))
                    .and(TagsTable.OWNER_ID.eq(userId))
                    .execute()

                SyncResult(opId = op.opId, status = "ok", entityId = op.entityId, version = 0)
            }
            else -> throw IllegalArgumentException("Unknown tag operation type: ${op.type}")
        }
    }
}

/**
 * Helper to parse JSONB data as a string.
 */
private fun org.jooq.JSONB.data(): String {
    return this.toString()
}