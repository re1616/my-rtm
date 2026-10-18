package com.taskly.server.sync

import com.taskly.server.db.Database
import com.taskly.server.db.LocationsTable
import com.taskly.server.db.*
import com.taskly.server.dto.*
import com.taskly.server.errors.ForbiddenException
import com.taskly.server.errors.NotFoundException
import com.taskly.server.tasks.TaskFields
import com.taskly.server.tasks.TaskService
import com.taskly.shared.domain.model.Uuid
import com.taskly.shared.sync.protocol.EntityState
import com.taskly.shared.sync.protocol.EntityType
import com.taskly.shared.sync.protocol.FieldVersions
import com.taskly.shared.sync.protocol.MergeEngine
import com.taskly.shared.sync.protocol.OpType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import com.taskly.shared.sync.protocol.SyncOperation as SharedOp

/**
 * Sync service for offline-first operations (SPEC F-5, ARCH 5).
 *
 * Guarantees:
 * - **Ownership**: an op can only touch entities owned by the caller; an id that already belongs
 *   to another user is rejected (never re-assigned).
 * - **Scopes** are derived on the server (`user:<id>`); a client-supplied foreign scope is rejected.
 * - **Isolation per op**: each op runs in its own savepoint, so one failing op neither aborts
 *   the batch nor rolls back the ops that were reported as `ok`.
 * - **Idempotency** by `(user_id, op_id)` in processed_ops; a replay returns the stored result.
 * - **LWW per field** via the shared [MergeEngine] and `field_versions`.
 * - **Recurrence**: `complete` goes through [TaskService.completeInTx] — the server is the single
 *   point that creates the next instance, and a duplicate completion never creates a second one.
 * - WebSocket signals are published only after commit.
 */
object SyncService {
    private val logger = LoggerFactory.getLogger("SyncService")
    private val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun processOperations(userId: String, ops: List<SyncOperation>, maxBatch: Int = 500): SyncResponse {
        require(ops.size <= maxBatch) { "Batch too large: ${ops.size} > $maxBatch" }
        val ownerId = UUID.fromString(userId)
        val personalScope = ChangeLog.personalScope(ownerId)
        val results = mutableListOf<SyncResult>()
        val changes = mutableListOf<ChangeLog.Change>()

        Database.ctx().transaction { config ->
            val tx = DSL.using(config)
            for (op in ops) {
                // Replay of an already processed op → stored result, no side effects
                val stored = tx.select(ProcessedOpsTable.RESULT).from(ProcessedOpsTable.TABLE)
                    .where(ProcessedOpsTable.USER_ID.eq(ownerId))
                    .and(ProcessedOpsTable.OP_ID.eq(op.opId))
                    .fetchOne()?.value1()
                if (stored != null) {
                    results += replayResult(op, stored)
                    continue
                }

                try {
                    // Savepoint: a failure here rolls back only this op
                    val outcome = tx.transactionResult { nested ->
                        val ntx = DSL.using(nested)
                        if (op.scope != personalScope) {
                            throw ForbiddenException("Scope '${op.scope}' is not allowed (expected '$personalScope')")
                        }
                        val applied = applyOperation(ntx, ownerId, op)
                        ntx.insertInto(ProcessedOpsTable.TABLE)
                            .set(ProcessedOpsTable.OP_ID, op.opId)
                            .set(ProcessedOpsTable.USER_ID, ownerId)
                            .set(ProcessedOpsTable.RESULT, JSONB.jsonb(encodeResult(applied.result)))
                            .execute()
                        applied
                    }
                    results += outcome.result
                    changes += outcome.changes
                } catch (e: Exception) {
                    val status = when (e) {
                        is ForbiddenException -> "forbidden"
                        is NotFoundException -> "not_found"
                        else -> "error"
                    }
                    logger.warn("Sync op failed: opId={}, entity={}, type={}, error={}", op.opId, op.entity, op.type, e.message)
                    results += SyncResult(
                        opId = op.opId,
                        status = status,
                        entityId = op.entityId,
                        error = if (e is IllegalArgumentException || e is ForbiddenException || e is NotFoundException) e.message else "Operation failed",
                    )
                }
            }
        }

        ChangeLog.publish(changes)
        return SyncResponse(results = results)
    }

    /** Delta since [afterSeq] for a scope the caller is allowed to read. */
    fun getChanges(userId: String, scopeId: String, afterSeq: Long? = null, limit: Int = 100): Pair<List<SyncChangeEntry>, Long?> {
        val ownerId = UUID.fromString(userId)
        if (scopeId !in ChangeLog.allowedScopes(ownerId)) {
            throw ForbiddenException("Scope '$scopeId' is not allowed")
        }
        var cond = ChangeLogTable.SCOPE_ID.eq(scopeId)
        if (afterSeq != null) cond = cond.and(ChangeLogTable.SEQ.gt(afterSeq))

        val records = Database.ctx().selectFrom(ChangeLogTable.TABLE)
            .where(cond)
            .orderBy(ChangeLogTable.SEQ.asc())
            .limit(limit.coerceIn(1, 500))
            .fetch()
        val lastSeq = records.lastOrNull()?.get(ChangeLogTable.SEQ)
        val entries = records.map { rec ->
            SyncChangeEntry(
                seq = rec.get(ChangeLogTable.SEQ),
                scopeId = rec.get(ChangeLogTable.SCOPE_ID),
                entity = rec.get(ChangeLogTable.ENTITY),
                entityId = rec.get(ChangeLogTable.ENTITY_ID).toString(),
                op = rec.get(ChangeLogTable.OP),
                payload = rec.get(ChangeLogTable.PAYLOAD).data(),
                actorId = rec.get(ChangeLogTable.ACTOR_ID).toString(),
                ts = rec.get(ChangeLogTable.TS).format(fmt),
            )
        }
        return entries to lastSeq
    }

    @kotlinx.serialization.Serializable
    data class SyncChangeEntry(
        val seq: Long,
        val scopeId: String,
        val entity: String,
        val entityId: String,
        val op: String,
        val payload: String? = null,
        val actorId: String,
        val ts: String,
    )

    // ============================================================
    // Operation handlers
    // ============================================================

    private data class Applied(val result: SyncResult, val changes: List<ChangeLog.Change>)

    private fun applyOperation(tx: DSLContext, ownerId: UUID, op: SyncOperation): Applied {
        val entityId = try {
            UUID.fromString(op.entityId)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("entityId must be a UUID")
        }
        return when (op.entity) {
            "task" -> applyTaskOp(tx, ownerId, entityId, op)
            "list" -> applyListOp(tx, ownerId, entityId, op)
            "tag" -> applyTagOp(tx, ownerId, entityId, op)
            "location" -> applyLocationOp(tx, ownerId, entityId, op)
            else -> throw IllegalArgumentException("Unknown entity: ${op.entity}")
        }
    }

    private fun applyTaskOp(tx: DSLContext, ownerId: UUID, id: UUID, op: SyncOperation): Applied {
        val fields = TaskFields.normalize(op.fields)

        // SEC-2: validate references before applying the operation
        validateRefs(tx, ownerId, fields)

        return when (op.type) {
            "create" -> {
                val existing = tx.selectFrom(TasksTable.TABLE).where(TasksTable.ID.eq(id)).forUpdate().fetchOne()
                if (existing != null) {
                    if (existing.get(TasksTable.OWNER_ID) != ownerId) {
                        throw ForbiddenException("Entity id is already in use")
                    }
                    // Same id re-created by its owner with a new op id → merge as an update
                    return mergeTaskUpdate(tx, ownerId, id, op, fields)
                }
                // Unknown, foreign or client-local list ids (e.g. "sys-inbox") fall back to Inbox
                val listId = (fields["list_id"] as? JsonPrimitive)?.contentOrNull
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?.takeIf { ownsList(tx, ownerId, it) }
                    ?: TaskService.inboxId(tx, ownerId)
                val withDefaults = fields - "list_id" + mapOf(
                    "list_id" to JsonPrimitive(listId.toString()),
                    "name" to (fields["name"]?.takeIf { it !is JsonNull } ?: JsonPrimitive("New Task")),
                )
                val columns = TaskFields.toColumns(withDefaults) { TaskService.requireOwnList(tx, ownerId, it) }
                val versions = TaskService.encodeFieldVersions(withDefaults.keys.associateWith { 1L })
                val rec = tx.insertInto(TasksTable.TABLE)
                    .set(TasksTable.ID, id)
                    .set(TasksTable.OWNER_ID, ownerId)
                    .set(columns)
                    .set(TasksTable.FIELD_VERSIONS, versions)
                    .returning(TasksTable.ALL)
                    .fetchOne()!!
                val version = rec.get(TasksTable.VERSION)
                val change = ChangeLog.append(tx, ownerId, "task", id, "create", TaskService.snapshotPayload(rec), version)
                Applied(ok(op, id, version), listOf(change))
            }
            "update", "move" -> mergeTaskUpdate(tx, ownerId, id, op, fields)
            "delete" -> {
                val change = TaskService.softDeleteInTx(tx, ownerId, id)
                Applied(ok(op, id, change?.version), listOfNotNull(change))
            }
            "complete" -> {
                val completedAt = fields["completed_at"]?.let { TaskFields.timestamp(it, "completed_at") } ?: OffsetDateTime.now()
                val outcome = TaskService.completeInTx(tx, ownerId, id, completedAt)
                Applied(ok(op, id, outcome.task.get(TasksTable.VERSION)), outcome.changes)
            }
            "uncomplete" -> {
                val change = TaskService.uncompleteInTx(tx, ownerId, id)
                Applied(ok(op, id, change?.version), listOfNotNull(change))
            }
            "tag_add", "tag_remove" -> {
                TaskService.lockOwnTask(tx, ownerId, id, includeDeleted = false)
                val tagName = TaskFields.str(op.fields?.get("tag"))?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("fields.tag is required")
                val tagId = TaskService.findTagByName(tx, ownerId, tagName)
                val changed = if (op.type == "tag_add") {
                    val tid = tagId ?: tx.insertInto(TagsTable.TABLE)
                        .set(TagsTable.OWNER_ID, ownerId)
                        .set(TagsTable.NAME, tagName)
                        .returning(TagsTable.ID)
                        .fetchOne()!!.get(TagsTable.ID)
                    tx.insertInto(TaskTagsTable.TABLE)
                        .set(TaskTagsTable.TASK_ID, id)
                        .set(TaskTagsTable.TAG_ID, tid)
                        .onConflictDoNothing()
                        .execute() > 0
                } else {
                    tagId != null && tx.deleteFrom(TaskTagsTable.TABLE)
                        .where(TaskTagsTable.TASK_ID.eq(id), TaskTagsTable.TAG_ID.eq(tagId))
                        .execute() > 0
                }
                val change = if (changed) {
                    ChangeLog.append(tx, ownerId, "task", id, op.type, buildJsonObject { put("tag", tagName) })
                } else null
                Applied(ok(op, id, null), listOfNotNull(change))
            }
            else -> throw IllegalArgumentException("Unknown task operation type: ${op.type}")
        }
    }

    /**
     * Field-level LWW using the shared [MergeEngine]: a field the server changed after the
     * client's base version is skipped; other fields are applied. Edits to a deleted task are
     * stored but the task stays deleted (delete wins).
     */
    private fun mergeTaskUpdate(tx: DSLContext, ownerId: UUID, id: UUID, op: SyncOperation, fields: Map<String, JsonElement>): Applied {
        val existing = TaskService.lockOwnTask(tx, ownerId, id, includeDeleted = true)
        val currentVersions = TaskService.parseFieldVersions(existing.get(TasksTable.FIELD_VERSIONS))
        // No base version → the client does not track versions: plain last-write-wins
        val baseVersion = op.baseVersion ?: (currentVersions.values.maxOrNull() ?: 0L)

        val state = EntityState(
            fields = emptyMap(),
            fieldVersions = FieldVersions(currentVersions),
            deleted = existing.get(TasksTable.DELETED_AT) != null,
        )
        val merged = MergeEngine.apply(
            state,
            SharedOp(
                opId = Uuid.generate(),
                scope = ChangeLog.personalScope(ownerId),
                entity = EntityType.TASK,
                entityId = Uuid.fromString(id.toString()),
                type = OpType.UPDATE,
                fields = fields,
                baseVersion = baseVersion,
                clientTs = kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                deviceId = op.deviceId ?: "unknown",
            ),
        )
        val accepted = fields - merged.skippedFields.toSet()
        if (accepted.isEmpty()) {
            return Applied(
                SyncResult(op.opId, "ok", id.toString(), existing.get(TasksTable.VERSION),
                    error = if (merged.skippedFields.isNotEmpty()) "skipped (newer on server): ${merged.skippedFields.joinToString()}" else null),
                emptyList(),
            )
        }

        val columns = TaskFields.toColumns(accepted) { TaskService.requireOwnList(tx, ownerId, it) }
        val newVersion = maxOf(existing.get(TasksTable.VERSION) + 1, merged.state.fieldVersions.versions.values.maxOrNull() ?: 0L)
        tx.update(TasksTable.TABLE)
            .set(columns)
            .set(TasksTable.FIELD_VERSIONS, TaskService.encodeFieldVersions(merged.state.fieldVersions.versions))
            .set(TasksTable.VERSION, newVersion)
            .set(TasksTable.UPDATED_AT, OffsetDateTime.now())
            .where(TasksTable.ID.eq(id))
            .execute()

        val change = ChangeLog.append(tx, ownerId, "task", id, op.type, JsonObject(accepted), newVersion)
        val note = if (merged.skippedFields.isNotEmpty()) "skipped (newer on server): ${merged.skippedFields.joinToString()}" else null
        return Applied(SyncResult(op.opId, "ok", id.toString(), newVersion, error = note), listOf(change))
    }

    private fun applyListOp(tx: DSLContext, ownerId: UUID, id: UUID, op: SyncOperation): Applied {
        val fields = op.fields.orEmpty()
        return when (op.type) {
            "create", "update" -> {
                val existing = tx.selectFrom(ListsTable.TABLE).where(ListsTable.ID.eq(id)).forUpdate().fetchOne()
                if (existing != null && existing.get(ListsTable.OWNER_ID) != ownerId) {
                    throw ForbiddenException("Entity id is already in use")
                }
                if (existing == null && op.type == "update") throw NotFoundException("List not found")

                val values = LinkedHashMap<org.jooq.Field<*>, Any?>()
                for ((key, el) in fields) {
                    when (key) {
                        "name" -> values[ListsTable.NAME] = TaskFields.str(el)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("name must be a non-empty string")
                        "color" -> values[ListsTable.COLOR] = TaskFields.str(el)
                        "sortOrder", "sort_order" -> values[ListsTable.SORT_ORDER] = TaskFields.str(el)?.toIntOrNull() ?: 0
                        "archived" -> values[ListsTable.ARCHIVED] = TaskFields.str(el)?.toBooleanStrictOrNull() ?: false
                        "query" -> values[ListsTable.QUERY] = TaskFields.str(el)
                        "isSmart", "is_smart" -> values[ListsTable.IS_SMART] = TaskFields.str(el)?.toBooleanStrictOrNull() ?: false
                    }
                }

                val version: Long
                if (existing == null) {
                    if (ListsTable.NAME !in values) values[ListsTable.NAME] = "New List"
                    version = tx.insertInto(ListsTable.TABLE)
                        .set(ListsTable.ID, id)
                        .set(ListsTable.OWNER_ID, ownerId)
                        .set(values)
                        .returning(ListsTable.VERSION)
                        .fetchOne()!!.get(ListsTable.VERSION)
                } else {
                    version = existing.get(ListsTable.VERSION) + 1
                    tx.update(ListsTable.TABLE)
                        .set(values)
                        .set(ListsTable.VERSION, version)
                        .set(ListsTable.UPDATED_AT, OffsetDateTime.now())
                        .where(ListsTable.ID.eq(id))
                        .execute()
                }
                val change = ChangeLog.append(tx, ownerId, "list", id, if (existing == null) "create" else "update", JsonObject(fields.mapValues { it.value ?: JsonNull }), version)
                Applied(ok(op, id, version), listOf(change))
            }
            "delete" -> {
                val n = tx.update(ListsTable.TABLE)
                    .set(ListsTable.DELETED_AT, OffsetDateTime.now())
                    .set(ListsTable.UPDATED_AT, OffsetDateTime.now())
                    .where(ListsTable.ID.eq(id))
                    .and(ListsTable.OWNER_ID.eq(ownerId))
                    .and(ListsTable.DELETED_AT.isNull)
                    .execute()
                val change = if (n > 0) ChangeLog.append(tx, ownerId, "list", id, "delete") else null
                Applied(ok(op, id, null), listOfNotNull(change))
            }
            else -> throw IllegalArgumentException("Unknown list operation type: ${op.type}")
        }
    }

    private fun applyTagOp(tx: DSLContext, ownerId: UUID, id: UUID, op: SyncOperation): Applied {
        return when (op.type) {
            "create" -> {
                val existing = tx.selectFrom(TagsTable.TABLE).where(TagsTable.ID.eq(id)).forUpdate().fetchOne()
                if (existing != null && existing.get(TagsTable.OWNER_ID) != ownerId) {
                    throw ForbiddenException("Entity id is already in use")
                }
                val name = TaskFields.str(op.fields?.get("name"))?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("name must be a non-empty string")
                val version = if (existing == null) {
                    tx.insertInto(TagsTable.TABLE)
                        .set(TagsTable.ID, id)
                        .set(TagsTable.OWNER_ID, ownerId)
                        .set(TagsTable.NAME, name)
                        .returning(TagsTable.VERSION)
                        .fetchOne()!!.get(TagsTable.VERSION)
                } else {
                    (existing.get(TagsTable.VERSION) + 1).also { v ->
                        tx.update(TagsTable.TABLE)
                            .set(TagsTable.NAME, name)
                            .set(TagsTable.VERSION, v)
                            .set(TagsTable.UPDATED_AT, OffsetDateTime.now())
                            .where(TagsTable.ID.eq(id))
                            .execute()
                    }
                }
                val change = ChangeLog.append(tx, ownerId, "tag", id, "create", buildJsonObject { put("name", name) }, version)
                Applied(ok(op, id, version), listOf(change))
            }
            "delete" -> {
                val n = tx.deleteFrom(TagsTable.TABLE)
                    .where(TagsTable.ID.eq(id))
                    .and(TagsTable.OWNER_ID.eq(ownerId))
                    .execute()
                val change = if (n > 0) ChangeLog.append(tx, ownerId, "tag", id, "delete") else null
                Applied(ok(op, id, null), listOfNotNull(change))
            }
            else -> throw IllegalArgumentException("Unknown tag operation type: ${op.type}")
        }
    }

    private fun applyLocationOp(tx: DSLContext, ownerId: UUID, id: UUID, op: SyncOperation): Applied {
        val fields = op.fields.orEmpty()
        return when (op.type) {
            "create", "update" -> {
                val existing = tx.selectFrom(LocationsTable.TABLE).where(LocationsTable.ID.eq(id)).forUpdate().fetchOne()
                if (existing != null && existing.get(LocationsTable.OWNER_ID) != ownerId) {
                    throw ForbiddenException("Entity id is already in use")
                }
                if (existing == null && op.type == "update") throw NotFoundException("Location not found")

                val name = TaskFields.str(fields["name"])?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("name must be a non-empty string")

                if (existing == null) {
                    tx.insertInto(LocationsTable.TABLE)
                        .set(LocationsTable.ID, id)
                        .set(LocationsTable.OWNER_ID, ownerId)
                        .set(LocationsTable.NAME, name)
                        .execute()
                } else {
                    tx.update(LocationsTable.TABLE)
                        .set(LocationsTable.NAME, name)
                        .where(LocationsTable.ID.eq(id))
                        .execute()
                }
                val change = ChangeLog.append(tx, ownerId, "location", id, if (existing == null) "create" else "update",
                    buildJsonObject { put("name", name) })
                Applied(ok(op, id, null), listOf(change))
            }
            "delete" -> {
                val n = tx.deleteFrom(LocationsTable.TABLE)
                    .where(LocationsTable.ID.eq(id))
                    .and(LocationsTable.OWNER_ID.eq(ownerId))
                    .execute()
                val change = if (n > 0) ChangeLog.append(tx, ownerId, "location", id, "delete") else null
                Applied(ok(op, id, null), listOfNotNull(change))
            }
            else -> throw IllegalArgumentException("Unknown location operation type: ${op.type}")
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * SEC-2: Validates that reference fields in a task operation (parent_id, location_id)
     * point to entities owned by the same user. Throws ForbiddenException for foreign references.
     */
    private fun validateRefs(tx: DSLContext, ownerId: UUID, fields: Map<String, JsonElement>) {
        val parentIdStr = (fields["parent_id"] as? JsonPrimitive)?.contentOrNull
        if (parentIdStr != null) {
            val parentId = try { UUID.fromString(parentIdStr) } catch (e: Exception) { return }
            val exists = tx.fetchExists(
                tx.selectOne().from(TasksTable.TABLE)
                    .where(TasksTable.ID.eq(parentId))
                    .and(TasksTable.OWNER_ID.eq(ownerId))
            )
            if (!exists) throw ForbiddenException("Reference parent_id=$parentId does not belong to the current scope")
        }

        val locationIdStr = (fields["location_id"] as? JsonPrimitive)?.contentOrNull
        if (locationIdStr != null) {
            val locationId = try { UUID.fromString(locationIdStr) } catch (e: Exception) { return }
            val exists = tx.fetchExists(
                tx.selectOne().from(LocationsTable.TABLE)
                    .where(LocationsTable.ID.eq(locationId))
                    .and(LocationsTable.OWNER_ID.eq(ownerId))
            )
            if (!exists) throw ForbiddenException("Reference location_id=$locationId does not belong to the current scope")
        }
    }

    private fun ownsList(tx: DSLContext, ownerId: UUID, listId: UUID): Boolean =
        tx.fetchExists(
            tx.selectOne().from(ListsTable.TABLE)
                .where(ListsTable.ID.eq(listId), ListsTable.OWNER_ID.eq(ownerId), ListsTable.DELETED_AT.isNull)
        )

    private fun ok(op: SyncOperation, id: UUID, version: Long?) =
        SyncResult(opId = op.opId, status = "ok", entityId = id.toString(), version = version)

    private fun encodeResult(r: SyncResult): String = buildJsonObject {
        put("status", r.status)
        put("entityId", r.entityId)
        r.version?.let { put("version", it) }
        r.error?.let { put("error", it) }
    }.toString()

    private fun replayResult(op: SyncOperation, stored: JSONB): SyncResult {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(stored.data()) as? JsonObject
        } catch (e: Exception) { null }
        fun s(k: String) = (obj?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return SyncResult(
            opId = op.opId,
            status = s("status") ?: "ok",
            entityId = s("entityId") ?: op.entityId,
            version = s("version")?.toLongOrNull(),
            error = s("error"),
        )
    }
}
