package com.taskly.android.core.sync

import com.taskly.android.core.database.ListEntity
import com.taskly.android.core.database.LocationEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.shared.domain.model.Priority
import com.taskly.shared.domain.model.Task
import com.taskly.shared.domain.model.Uuid
import com.taskly.shared.query.QueryParser
import com.taskly.shared.query.QueryPredicate
import com.taskly.shared.query.TaskEvalContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * Smart lists and query search on Android (AND-S7): the shared query language and predicate
 * (ARCH 3.1, QA-3) evaluated over the Room data — the same result as the server and the web.
 */
object SmartLists {

    /** Parse error message, or null when [query] is valid. */
    fun problem(query: String): String? = try {
        QueryParser.parse(query); null
    } catch (e: Exception) {
        e.message ?: "Invalid query"
    }

    fun filter(
        query: String,
        tasks: List<TaskEntity>,
        lists: List<ListEntity>,
        locations: List<LocationEntity>,
        tagNames: Map<String, List<String>>,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<TaskEntity> {
        val predicate = QueryPredicate.build(QueryParser.parse(query))
        val ids = Ids()
        val ctx = TaskEvalContext(
            now = now,
            timeZone = zone,
            listNames = lists.associate { ids.of(it.id) to it.name },
            locationNames = locations.associate { ids.of(it.id) to it.name },
            parentTaskIds = tasks.mapNotNull { t -> t.parentId?.let { ids.of(it) } }.toSet(),
        )
        return tasks.filter { t -> predicate(t.toShared(ids, tagNames[t.id].orEmpty(), now), ctx) }
    }

    /** Convenience: everything from Room for one user. */
    suspend fun run(db: TasklyDatabase, userId: String, query: String, zone: TimeZone = TimeZone.currentSystemDefault()): List<TaskEntity> {
        val tags = db.tagDao().allTaskTagNames().groupBy({ it.taskId }, { it.name })
        return filter(query, db.taskDao().allLive(userId), db.listDao().allNow(userId), db.locationDao().allNow(userId), tags, zone = zone)
    }

    /** String ids → shared Uuid; local placeholders like "inbox" get stable synthetic ids. */
    private class Ids {
        private val synthetic = HashMap<String, Uuid>()
        fun of(raw: String): Uuid = runCatching { Uuid.fromString(raw) }.getOrNull()
            ?: synthetic.getOrPut(raw) { Uuid.fromString("00000000-0000-4000-8000-" + (synthetic.size + 1).toString(16).padStart(12, '0')) }
    }

    private fun parse(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun TaskEntity.toShared(ids: Ids, tags: List<String>, now: Instant) = Task(
        id = ids.of(id),
        name = name,
        listId = ids.of(listId),
        parentId = parentId?.let { ids.of(it) },
        priority = Priority.fromLevel(priority),
        dueAt = parse(dueAt),
        dueHasTime = dueHasTime,
        startAt = parse(startAt),
        estimateMin = estimateMin,
        rrule = rrule,
        locationId = locationId?.let { ids.of(it) },
        completedAt = parse(completedAt),
        postponedCount = postponedCount,
        tagNames = tags,
        createdAt = parse(createdAt) ?: now,
        updatedAt = parse(updatedAt) ?: now,
    )
}
