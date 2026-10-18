@file:OptIn(ExperimentalJsExport::class)

package com.taskly.shared.js

import com.taskly.shared.domain.model.Priority
import com.taskly.shared.domain.model.Task
import com.taskly.shared.domain.model.Uuid
import com.taskly.shared.query.QueryParseException
import com.taskly.shared.query.QueryParser
import com.taskly.shared.query.QueryPredicate
import com.taskly.shared.query.TaskEvalContext
import com.taskly.shared.smartadd.SmartAddParser
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JS entry points of the shared module for the web client (ARCH 3.1: one domain logic for
 * server, Android and web). Results cross the boundary as JSON strings so the API stays
 * independent of Kotlin types (LocalDate, Instant, enums) that are not exportable to JS.
 */

private val webJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
internal data class WebToken(val type: String, val start: Int, val end: Int, val value: String)

@Serializable
internal data class WebSmartAdd(
    val name: String,
    val tokens: List<WebToken>,
    /** ISO-8601 instant of the due date (local midnight when no time was given). */
    val dueInstant: String? = null,
    /** yyyy-MM-dd in the parse time zone. */
    val dueDate: String? = null,
    val dueHasTime: Boolean = false,
    val startInstant: String? = null,
    val startDate: String? = null,
    val priority: Int? = null,
    val listName: String? = null,
    val tags: List<String> = emptyList(),
    val rrule: String? = null,
    val repeatMode: String? = null,
    val location: String? = null,
    val estimateMin: Int? = null,
    val contact: String? = null,
    val note: String? = null,
)

/**
 * Resolves the time zone: IANA name when the runtime supports it, otherwise the fixed
 * UTC offset the browser reports (e.g. "UTC+03:00").
 */
private fun zone(timeZone: String, fallbackOffset: String): TimeZone =
    runCatching { TimeZone.of(timeZone) }.getOrNull()
        ?: runCatching { TimeZone.of(fallbackOffset) }.getOrNull()
        ?: TimeZone.UTC

/**
 * Smart Add (SPEC F-2) for the web: the same parser the server and Android use.
 * @param today yyyy-MM-dd in the user's zone (pinned for determinism and tests)
 * @return JSON of [WebSmartAdd]
 */
@JsExport
fun parseSmartAdd(
    input: String,
    today: String,
    timeZone: String,
    fallbackOffset: String,
    listNames: Array<String>,
    tagNames: Array<String>,
): String {
    val tz = zone(timeZone, fallbackOffset)
    val r = SmartAddParser().parse(input, LocalDate.parse(today), tz, listNames.toSet(), tagNames.toSet())
    val dto = WebSmartAdd(
        name = r.name,
        tokens = r.tokens.map { WebToken(it.type.name.lowercase(), it.start, it.end, it.value) },
        dueInstant = r.due?.instant?.toString(),
        dueDate = r.due?.date?.toString(),
        dueHasTime = r.due?.hasTime ?: false,
        startInstant = r.start?.instant?.toString(),
        startDate = r.start?.date?.toString(),
        priority = r.priority?.level,
        listName = r.listName,
        tags = r.tags,
        rrule = r.repeat?.rrule,
        repeatMode = r.repeat?.mode?.name?.lowercase(),
        location = r.location,
        estimateMin = r.estimateMin,
        contact = r.contact,
        note = r.note,
    )
    return webJson.encodeToString(WebSmartAdd.serializer(), dto)
}

/** Validates a smart-list query; returns null when valid, otherwise the parser's message. */
@JsExport
fun validateQuery(query: String): String? =
    try {
        QueryParser.parse(query)
        null
    } catch (e: Exception) {
        e.message ?: "Invalid query"
    }

/** Today's date (yyyy-MM-dd) in the given zone — helper for callers without a date library. */
@JsExport
fun todayIn(epochMillis: Double, timeZone: String, fallbackOffset: String): String =
    kotlinx.datetime.Instant.fromEpochMilliseconds(epochMillis.toLong())
        .toLocalDateTime(zone(timeZone, fallbackOffset)).date.toString()

// ── Smart lists on the client (WEB-F5, QA-3) ──────────────────────────────────

@Serializable
internal data class WebTask(
    val id: String,
    val name: String = "",
    val listId: String? = null,
    val parentId: String? = null,
    val locationId: String? = null,
    /** 1, 2, 3 or null. */
    val priority: Int? = null,
    val dueAt: String? = null,
    val dueHasTime: Boolean = false,
    val startAt: String? = null,
    val estimateMin: Int? = null,
    val rrule: String? = null,
    val completedAt: String? = null,
    val postponedCount: Int = 0,
    val tags: List<String> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class WebNamed(val id: String, val name: String)

@Serializable
internal data class WebQueryContext(
    val lists: List<WebNamed> = emptyList(),
    val locations: List<WebNamed> = emptyList(),
    val sharedListIds: List<String> = emptyList(),
)

@Serializable
internal data class WebFilterResult(val ids: List<String> = emptyList(), val error: String? = null)

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Evaluates a smart-list query over the web client's local tasks with the SAME predicate the
 * server compiles to SQL and Android runs over Room (ARCH 3.1, QA-3).
 *
 * Web ids are not always UUIDs (system lists such as "sys-inbox"); they are mapped to stable
 * synthetic UUIDs for the evaluation and back, so the predicate code stays unchanged.
 *
 * @param tasksJson JSON array of [WebTask]
 * @param contextJson JSON of [WebQueryContext] (list and location names, shared lists)
 * @return JSON of [WebFilterResult]: matching task ids in input order, or the parse error
 */
@JsExport
fun filterTasks(
    query: String,
    tasksJson: String,
    nowEpochMillis: Double,
    timeZone: String,
    fallbackOffset: String,
    contextJson: String,
): String {
    val result = try {
        val expr = QueryParser.parse(query)
        val tasks = lenientJson.decodeFromString(ListSerializer(WebTask.serializer()), tasksJson)
        val context = lenientJson.decodeFromString(WebQueryContext.serializer(), contextJson.ifBlank { "{}" })
        val ids = IdMapper()
        val now = Instant.fromEpochMilliseconds(nowEpochMillis.toLong())
        val ctx = TaskEvalContext(
            now = now,
            timeZone = zone(timeZone, fallbackOffset),
            listNames = context.lists.associate { ids.of(it.id) to it.name },
            locationNames = context.locations.associate { ids.of(it.id) to it.name },
            sharedListIds = context.sharedListIds.map { ids.of(it) }.toSet(),
            parentTaskIds = tasks.mapNotNull { t -> t.parentId?.let { ids.of(it) } }.toSet(),
        )
        val predicate = QueryPredicate.build(expr)
        val matching = tasks.filter { predicate(it.toTask(ids, now), ctx) }.map { it.id }
        WebFilterResult(ids = matching)
    } catch (e: Exception) {
        WebFilterResult(error = e.message ?: "Invalid query")
    }
    return webJson.encodeToString(WebFilterResult.serializer(), result)
}

/** Stable string id → UUID mapping for one evaluation (real UUIDs map to themselves). */
private class IdMapper {
    private val synthetic = HashMap<String, Uuid>()

    fun of(raw: String): Uuid =
        runCatching { Uuid.fromString(raw) }.getOrNull()
            ?: synthetic.getOrPut(raw) {
                val n = (synthetic.size + 1).toString(16).padStart(12, '0')
                Uuid.fromString("00000000-0000-4000-8000-$n")
            }
}

private fun parseInstant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun WebTask.toTask(ids: IdMapper, now: Instant): Task = Task(
    id = ids.of(id),
    name = name,
    listId = listId?.let { ids.of(it) },
    parentId = parentId?.let { ids.of(it) },
    priority = Priority.fromLevel(priority),
    dueAt = parseInstant(dueAt),
    dueHasTime = dueHasTime,
    startAt = parseInstant(startAt),
    estimateMin = estimateMin,
    rrule = rrule,
    locationId = locationId?.let { ids.of(it) },
    completedAt = parseInstant(completedAt),
    postponedCount = postponedCount,
    tagNames = tags,
    createdAt = parseInstant(createdAt) ?: now,
    updatedAt = parseInstant(updatedAt) ?: now,
)

@Serializable
internal data class WebQueryError(val message: String, val position: Int? = null)

/**
 * Smart-list query check for the editor (WEB-F5): null when valid, otherwise JSON of
 * [WebQueryError] with the character position to highlight.
 */
@JsExport
fun queryError(query: String): String? =
    try {
        QueryParser.parse(query)
        null
    } catch (e: QueryParseException) {
        webJson.encodeToString(WebQueryError.serializer(), WebQueryError(e.message ?: "Invalid query", e.position))
    } catch (e: Exception) {
        webJson.encodeToString(WebQueryError.serializer(), WebQueryError(e.message ?: "Invalid query"))
    }
