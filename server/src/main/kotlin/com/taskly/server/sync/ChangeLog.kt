package com.taskly.server.sync

import com.taskly.server.db.ChangeLogTable
import com.taskly.server.redis.RedisService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Single place that writes the server change log and fans out "there are changes" signals.
 *
 * Scopes are always derived on the server: a user may only read and write their own
 * personal scope `user:<id>`. Shared-list scopes (`list:<id>`) are not implemented yet,
 * so any other scope is rejected rather than trusted from the client.
 */
object ChangeLog {
    private val logger = LoggerFactory.getLogger("ChangeLog")

    fun personalScope(userId: UUID): String = "user:$userId"

    /** Scopes this user may read/subscribe to. */
    fun allowedScopes(userId: UUID): Set<String> = setOf(personalScope(userId))

    data class Change(
        val userId: UUID,
        val scope: String,
        val seq: Long,
        val entity: String,
        val entityId: UUID,
        val op: String,
        val version: Long?,
    )

    /** Appends one entry to change_log inside the caller's transaction. */
    fun append(
        tx: DSLContext,
        userId: UUID,
        entity: String,
        entityId: UUID,
        op: String,
        payload: JsonObject = JsonObject(emptyMap()),
        version: Long? = null,
    ): Change {
        val scope = personalScope(userId)
        val seq = tx.insertInto(ChangeLogTable.TABLE)
            .set(ChangeLogTable.SCOPE_ID, scope)
            .set(ChangeLogTable.ENTITY, entity)
            .set(ChangeLogTable.ENTITY_ID, entityId)
            .set(ChangeLogTable.OP, op)
            .set(ChangeLogTable.PAYLOAD, JSONB.jsonb(payload.toString()))
            .set(ChangeLogTable.ACTOR_ID, userId)
            .returning(ChangeLogTable.SEQ)
            .fetchOne()!!
            .get(ChangeLogTable.SEQ)
        return Change(userId, scope, seq, entity, entityId, op, version)
    }

    /**
     * Publishes WebSocket signals. Must be called only AFTER the transaction committed,
     * otherwise clients may fetch the delta before the data is visible.
     */
    fun publish(changes: List<Change>) {
        for (c in changes) {
            val message = buildJsonObject {
                put("seq", c.seq)
                put("scope", c.scope)
                put("op", c.op)
                put("entity", c.entity)
                put("entityId", c.entityId.toString())
                c.version?.let { put("version", it) }
            }
            try {
                RedisService.publish(channel(c.userId, c.scope), message.toString())
            } catch (e: Exception) {
                // Non-critical: clients catch up by cursor on the next pull.
                logger.debug("Redis publish failed: {}", e.message)
            }
        }
    }

    fun channel(userId: UUID, scope: String): String = "sync:$userId:$scope"
}
