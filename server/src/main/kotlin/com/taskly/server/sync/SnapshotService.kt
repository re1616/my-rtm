package com.taskly.server.sync

import com.taskly.server.db.ChangeLogTable
import com.taskly.server.db.Database
import com.taskly.server.db.LocationsTable
import com.taskly.server.db.TaskTagsTable
import com.taskly.server.db.TasksTable
import com.taskly.server.dto.ListResponse
import com.taskly.server.dto.TagResponse
import com.taskly.server.dto.TaskResponse
import com.taskly.server.lists.ListService
import com.taskly.server.tags.TagService
import com.taskly.server.tasks.TaskService
import kotlinx.serialization.Serializable
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class LocationResponse(val id: String, val name: String)

/**
 * One page of the initial snapshot (WEB-S4, AND-S6).
 *
 * [seq] is only on the first page: the change-log position the snapshot starts from. Clients
 * store it after the last page and continue with `/v1/sync/changes?after=seq`, so changes made
 * while paging are replayed (applying them is idempotent) and nothing is missed.
 */
@Serializable
data class SnapshotResponse(
    val seq: Long? = null,
    val tasks: List<TaskResponse>,
    val lists: List<ListResponse> = emptyList(),
    val tags: List<TagResponse> = emptyList(),
    val locations: List<LocationResponse> = emptyList(),
    /** Pass as `cursor` for the next page; null on the last page. */
    val nextCursor: String? = null,
)

object SnapshotService {
    const val MAX_PAGE = 1000

    /** Open tasks plus tasks completed in the last [COMPLETED_DAYS] days (older ones load on demand). */
    const val COMPLETED_DAYS = 90L

    fun page(userId: String, cursor: String?, limit: Int): SnapshotResponse {
        val ownerId = UUID.fromString(userId)
        val after = cursor?.let {
            runCatching { UUID.fromString(it) }.getOrElse { throw IllegalArgumentException("Invalid snapshot cursor") }
        }
        val size = limit.coerceIn(1, MAX_PAGE)
        val ctx = Database.ctx()
        val first = after == null

        // Read the position BEFORE the data, so nothing committed in between is skipped
        val seq = if (first) {
            ctx.select(DSL.max(ChangeLogTable.SEQ)).from(ChangeLogTable.TABLE)
                .where(ChangeLogTable.SCOPE_ID.eq(ChangeLog.personalScope(ownerId)))
                .fetchOne()?.value1() ?: 0L
        } else null

        var condition = TasksTable.OWNER_ID.eq(ownerId)
            .and(TasksTable.DELETED_AT.isNull)
            .and(TasksTable.COMPLETED_AT.isNull.or(TasksTable.COMPLETED_AT.gt(OffsetDateTime.now().minusDays(COMPLETED_DAYS))))
        if (after != null) condition = condition.and(TasksTable.ID.gt(after))

        val rows = ctx.selectFrom(TasksTable.TABLE).where(condition).orderBy(TasksTable.ID).limit(size + 1).fetch()
        val pageRows = rows.take(size)
        val ids = pageRows.map { it.get(TasksTable.ID) }
        val tagsByTask = if (ids.isEmpty()) emptyMap() else
            ctx.select(TaskTagsTable.TASK_ID, TaskTagsTable.TAG_ID).from(TaskTagsTable.TABLE)
                .where(TaskTagsTable.TASK_ID.`in`(ids))
                .fetch()
                .groupBy({ it.value1() }, { it.value2().toString() })

        return SnapshotResponse(
            seq = seq,
            tasks = pageRows.map { TaskService.toResponse(it, tagsByTask[it.get(TasksTable.ID)] ?: emptyList()) },
            lists = if (first) ListService.list(userId) else emptyList(),
            tags = if (first) TagService.list(userId) else emptyList(),
            locations = if (first) {
                ctx.select(LocationsTable.ID, LocationsTable.NAME).from(LocationsTable.TABLE)
                    .where(LocationsTable.OWNER_ID.eq(ownerId))
                    .fetch { LocationResponse(it.value1().toString(), it.value2()) }
            } else emptyList(),
            nextCursor = if (rows.size > size) ids.last().toString() else null,
        )
    }
}
