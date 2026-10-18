package com.taskly.server.tasks

import com.taskly.server.db.TasksTable
import com.taskly.shared.recurrence.RRule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jooq.Field
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Typed decoding of task fields coming from sync operations.
 *
 * Accepts both camelCase (web client) and snake_case (shared protocol / ARCH) names and
 * always works with canonical snake_case internally. JSON `null` means "clear the field"
 * (it is never turned into the string "null").
 */
object TaskFields {

    private val aliases = mapOf(
        "name" to "name",
        "listId" to "list_id", "list_id" to "list_id",
        "parentId" to "parent_id", "parent_id" to "parent_id",
        "locationId" to "location_id", "location_id" to "location_id",
        "priority" to "priority",
        "dueAt" to "due_at", "due_at" to "due_at",
        "dueHasTime" to "due_has_time", "due_has_time" to "due_has_time",
        "startAt" to "start_at", "start_at" to "start_at",
        "estimateMin" to "estimate_min", "estimate_min" to "estimate_min",
        "rrule" to "rrule",
        "repeatMode" to "repeat_mode", "repeat_mode" to "repeat_mode",
        "url" to "url",
        "completedAt" to "completed_at", "completed_at" to "completed_at",
        "postponedCount" to "postponed_count", "postponed_count" to "postponed_count",
    )

    /** Canonical snake_case name, or null for fields the server does not accept. */
    fun canonical(key: String): String? = aliases[key]

    /** Normalizes keys to snake_case and drops unknown fields. */
    fun normalize(fields: Map<String, JsonElement?>?): Map<String, JsonElement> =
        fields.orEmpty().mapNotNull { (k, v) -> canonical(k)?.let { it to (v ?: JsonNull) } }.toMap()

    /**
     * Decodes canonical fields into column → value assignments.
     * [listOwnerCheck] validates that a referenced list belongs to the user.
     */
    fun toColumns(fields: Map<String, JsonElement>, listOwnerCheck: (UUID) -> Unit): Map<Field<*>, Any?> {
        val out = LinkedHashMap<Field<*>, Any?>()
        for ((key, el) in fields) {
            when (key) {
                "name" -> out[TasksTable.NAME] = str(el)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("name must be a non-empty string")
                "list_id" -> {
                    val id = uuid(el) ?: throw IllegalArgumentException("list_id cannot be null")
                    listOwnerCheck(id)
                    out[TasksTable.LIST_ID] = id
                }
                "parent_id" -> {
                    val id = uuid(el)
                    out[TasksTable.PARENT_ID] = id
                }
                "location_id" -> {
                    val id = uuid(el)
                    out[TasksTable.LOCATION_ID] = id
                }
                "priority" -> out[TasksTable.PRIORITY] = priority(el)
                "due_at" -> out[TasksTable.DUE_AT] = timestamp(el, key)
                "due_has_time" -> out[TasksTable.DUE_HAS_TIME] = bool(el) ?: false
                "start_at" -> out[TasksTable.START_AT] = timestamp(el, key)
                "estimate_min" -> out[TasksTable.ESTIMATE_MIN] = int(el)?.also {
                    require(it >= 0) { "estimate_min must be >= 0" }
                }
                "rrule" -> out[TasksTable.RRULE] = str(el)?.also {
                    require(RRule.parse(it) != null) { "Invalid rrule: $it" }
                }
                "repeat_mode" -> out[TasksTable.REPEAT_MODE] = str(el)?.lowercase()?.also {
                    require(it == "every" || it == "after") { "repeat_mode must be 'every' or 'after'" }
                }
                "url" -> out[TasksTable.URL] = str(el)
                "completed_at" -> out[TasksTable.COMPLETED_AT] = timestamp(el, key)
                "postponed_count" -> out[TasksTable.POSTPONED_COUNT] = (int(el) ?: 0).also {
                    require(it >= 0) { "postponed_count must be >= 0" }
                }
            }
        }
        return out
    }

    fun str(el: JsonElement?): String? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> el.contentOrNull
        else -> throw IllegalArgumentException("Expected a string, got $el")
    }

    private fun int(el: JsonElement?): Int? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> el.intOrNull ?: el.contentOrNull?.toIntOrNull()
            ?: throw IllegalArgumentException("Expected an integer, got $el")
        else -> throw IllegalArgumentException("Expected an integer, got $el")
    }

    private fun bool(el: JsonElement?): Boolean? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> el.booleanOrNull ?: el.contentOrNull?.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("Expected a boolean, got $el")
        else -> throw IllegalArgumentException("Expected a boolean, got $el")
    }

    private fun uuid(el: JsonElement?): UUID? = str(el)?.let {
        try { UUID.fromString(it) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("Invalid UUID: $it") }
    }

    /** 1/2/3, or null/0 for "no priority". */
    fun priority(el: JsonElement?): Int? {
        val p = int(el) ?: return null
        if (p == 0) return null
        require(p in 1..3) { "priority must be 1, 2, 3 or null" }
        return p
    }

    fun timestamp(el: JsonElement?, key: String): OffsetDateTime? = str(el)?.let { parseTimestamp(it, key) }

    fun parseTimestamp(raw: String, key: String = "timestamp"): OffsetDateTime = try {
        OffsetDateTime.parse(raw)
    } catch (e: DateTimeParseException) {
        try {
            Instant.parse(raw).atOffset(ZoneOffset.UTC)
        } catch (e2: DateTimeParseException) {
            throw IllegalArgumentException("$key must be an ISO-8601 timestamp, got '$raw'")
        }
    }
}
