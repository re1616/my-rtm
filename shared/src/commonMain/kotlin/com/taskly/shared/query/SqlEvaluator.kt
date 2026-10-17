package com.taskly.shared.query

import com.taskly.shared.domain.model.Task
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Буквальный оценщик SQL-предикатов [SqlPred] над задачей + контекстом.
 * Семантика должна соответствовать рендереному SQL (PostgreSQL) для эквивалентности.
 * Только фактические поля задачи — без join-подзапросов (ListName/TagName/... эмулируются
 * через [TaskEvalContext]).
 */
object SqlEvaluator {

    fun eval(pred: SqlPred, task: Task, ctx: TaskEvalContext): Boolean = when (pred) {
        is SqlPred.And -> pred.parts.all { eval(it, task, ctx) }
        is SqlPred.Or -> pred.parts.any { eval(it, task, ctx) }
        is SqlPred.Not -> !eval(pred.part, task, ctx)
        is SqlPred.Column -> evalColumn(pred, task, ctx)
        is SqlPred.ColumnNull -> evalNull(pred, task)
        is SqlPred.HasSubtasks -> (task.id in ctx.parentTaskIds) != pred.negated
        is SqlPred.ListName -> {
            val name = ctx.listNames[task.listId] ?: ""
            (name.equals(pred.name, ignoreCase = true)) != pred.negated
        }
        is SqlPred.TagName -> {
            val tags = ctx.tagNamesById[task.id] ?: task.tagNames
            tags.any { it.equals(pred.name, ignoreCase = true) } != pred.negated
        }
        is SqlPred.LocationName -> {
            val locName = task.locationId?.let { ctx.locationNames[it] } ?: ""
            (locName.equals(pred.name, ignoreCase = true)) != pred.negated
        }
        is SqlPred.SharedList -> {
            val shared = task.listId != null && task.listId in ctx.sharedListIds
            shared != pred.negated
        }
    }

    private fun evalColumn(pred: SqlPred.Column, task: Task, ctx: TaskEvalContext): Boolean {
        val actual = colValue(pred.col, task)
        if (actual == null) return false // NULL в SQL → FALSE (если только не IS NULL)
        return when (pred.param) {
            is Param.Str -> {
                if (pred.op == CmpOp.CONTAINS) {
                    val term = pred.param.value
                    // SQL: lower(name) LIKE lower('%term%') → param уже содержит %
                    val cleanTerm = term.trim('%')
                    (actual as String).lowercase().contains(cleanTerm.lowercase())
                } else {
                    val s = actual as String
                    s.lowercase() == pred.param.value.lowercase()
                }
            }
            is Param.Num -> {
                val n = (actual as Int).toLong()
                val expected = pred.param.value
                cmpNum(n, expected, pred.op)
            }
            is Param.Ts -> {
                val t = actual as Long
                val expectedMs = parseTs(pred.param.value)
                cmpNum(t, expectedMs, pred.op)
            }
        }
    }

    private fun evalNull(pred: SqlPred.ColumnNull, task: Task): Boolean {
        val col = pred.col
        val value: Any? = when (col) {
            TaskCol.RRULE -> task.rrule
            TaskCol.REPEAT_MODE -> task.repeatMode?.name
            TaskCol.COMPLETED_AT -> task.completedAt
            TaskCol.PRIORITY -> task.priority.level // NONE → null (как NULL в БД)
            TaskCol.DUE_AT -> task.dueAt
            TaskCol.LOCATION_ID -> task.locationId
            else -> null
        }
        val isNull = value == null
        return isNull != pred.negated
    }

    private fun colValue(col: TaskCol, task: Task): Any? = when (col) {
        TaskCol.NAME -> if (task.name.isNotEmpty()) task.name else null
        TaskCol.PRIORITY -> task.priority.level  // null для NONE
        TaskCol.DUE_AT -> task.dueAt?.toEpochMilliseconds()
        TaskCol.COMPLETED_AT -> task.completedAt?.toEpochMilliseconds()
        TaskCol.RRULE -> task.rrule
        TaskCol.REPEAT_MODE -> task.repeatMode?.name
        TaskCol.LOCATION_ID -> task.locationId?.toString()
        TaskCol.POSTPONED -> task.postponedCount
        TaskCol.ESTIMATE_MIN -> task.estimateMin
        TaskCol.CREATED_AT -> task.createdAt.toEpochMilliseconds()
    }

    private fun cmpNum(actual: Long, expected: Long, op: CmpOp): Boolean = when (op) {
        CmpOp.EQ -> actual == expected
        CmpOp.LT -> actual < expected
        CmpOp.LTE -> actual <= expected
        CmpOp.GT -> actual > expected
        CmpOp.GTE -> actual >= expected
        CmpOp.CONTAINS -> false
    }

    private fun parseTs(s: String): Long {
        // ISO → epoch ms
        return Instant.parse(s).toEpochMilliseconds()
    }
}