package com.taskly.shared.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** filterTasks — the web's client-side smart lists run the shared predicate (WEB-F5, QA-3). */
class FilterTasksTest {

    // 2026-10-18T12:00:00Z
    private val now = 1_792_324_800_000.0

    private val tasks = """
        [
          {"id":"a","name":"Report","listId":"work-1","priority":1,"dueAt":"2026-10-18T15:00:00Z","tags":["office"]},
          {"id":"b","name":"Milk","listId":"sys-inbox","priority":2,"dueAt":"2026-10-25T09:00:00Z","tags":[]},
          {"id":"c","name":"Old","listId":"work-1","completedAt":"2026-10-10T10:00:00Z","tags":["office"]},
          {"id":"d","name":"Child","listId":"work-1","parentId":"a","postponedCount":3,"tags":[]}
        ]
    """.trimIndent()

    private val context = """{"lists":[{"id":"work-1","name":"Work"},{"id":"sys-inbox","name":"Inbox"}]}"""

    private fun run(query: String): Pair<List<String>, String?> {
        val out = Json.parseToJsonElement(filterTasks(query, tasks, now, "UTC", "UTC+00:00", context)).jsonObject
        val ids = out["ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return ids to out["error"]?.jsonPrimitive?.content
    }

    @Test
    fun listTagAndPriorityWithOperators() {
        assertEquals(listOf("a"), run("list:Work AND tag:office AND completed:false").first)
        assertEquals(listOf("a", "b"), run("priority:1 OR priority:2").first)
        assertEquals(listOf("b"), run("list:Inbox").first, "non-UUID local list ids resolve by name")
    }

    @Test
    fun dueAndSubtasksAndPostponed() {
        assertEquals(listOf("a"), run("due:today").first)
        assertEquals(listOf("a"), run("hasSubtasks:true").first)
        assertEquals(listOf("d"), run("postponed:>2").first)
        assertEquals(listOf("c"), run("completed:true").first)
    }

    @Test
    fun invalidQueryReportsAnError() {
        val (ids, error) = run("priority:1 AND (")
        assertEquals(emptyList(), ids)
        assertNotNull(error)
        assertNull(run("NOT completed:true").second)
    }
}

class QueryErrorTest {
    @Test
    fun reportsPositionOfTheProblem() {
        assertNull(queryError("tag:office AND due:today"))
        val err = Json.parseToJsonElement(queryError("tag:office AND )")!!).jsonObject
        assertNotNull(err["message"])
        assertNotNull(err["position"]?.jsonPrimitive?.content?.toIntOrNull())
    }
}
