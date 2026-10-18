package com.taskly.shared.query

import com.taskly.shared.domain.model.Task
import com.taskly.shared.domain.model.Uuid
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant

/** Колонки таблицы tasks (алиас t) в SQL-генераторе. */
enum class TaskCol { NAME, PRIORITY, DUE_AT, COMPLETED_AT, RRULE, REPEAT_MODE, LOCATION_ID, POSTPONED, ESTIMATE_MIN, CREATED_AT }

/** Параметр SQL-запроса (rendered as '?' с привязкой). */
sealed interface Param {
    data class Str(val value: String) : Param
    data class Num(val value: Long) : Param
    data class Ts(val value: String) : Param // ISO-8601
}

/**
 * Структурный SQL-предикат: понятие, из которого рендерится WHERE-выражение
 * (для сервера, jOOQ/PostgreSQL) и которое буквально исполняется оценщиком
 * [SqlEvaluator] — за счёт этого доказывается эквивалентность AST→предикат и AST→SQL.
 */
sealed interface SqlPred {
    data class And(val parts: List<SqlPred>) : SqlPred
    data class Or(val parts: List<SqlPred>) : SqlPred
    data class Not(val part: SqlPred) : SqlPred

    /** t.<col> <op> <param>. */
    data class Column(val col: TaskCol, val op: CmpOp, val param: Param) : SqlPred

    /** t.<col> IS [NOT] NULL (negated=true → IS NOT NULL). */
    data class ColumnNull(val col: TaskCol, val negated: Boolean) : SqlPred

    /** EXISTS (SELECT 1 FROM tasks c WHERE c.parent_id = t.id). */
    data class HasSubtasks(val negated: Boolean) : SqlPred

    /** EXISTS (SELECT 1 FROM lists l WHERE l.id = t.list_id AND lower(l.name) = lower(?) ). */
    data class ListName(val name: String, val negated: Boolean) : SqlPred

    /** EXISTS (SELECT 1 FROM task_tags tt JOIN tags tg ON tg.id = tt.tag_id WHERE tt.task_id = t.id AND lower(tg.name) = lower(?)). */
    data class TagName(val name: String, val negated: Boolean) : SqlPred

    /** EXISTS (SELECT 1 FROM locations loc WHERE loc.id = t.location_id AND lower(loc.name) = lower(?)). */
    data class LocationName(val name: String, val negated: Boolean) : SqlPred

    /** EXISTS (SELECT 1 FROM list_members lm WHERE lm.list_id = t.list_id AND lm.permission = 'view'). */
    data class SharedList(val negated: Boolean) : SqlPred
}

/** Рендер структурного SQL-предиката в WHERE-строку + параметры (ORDER: ? binds). */
object SqlRenderer {

    fun render(pred: SqlPred, alias: String = "t"): Pair<String, List<Param>> {
        val params = mutableListOf<Param>()
        val sql = renderNode(pred, alias, params)
        return "( $sql )" to params
    }

    private fun renderNode(pred: SqlPred, alias: String, params: MutableList<Param>): String = when (pred) {
        // Every operand is parenthesized: `a AND (b OR c)` must not render as `a AND b OR c`
        is SqlPred.And -> pred.parts.joinToString(" AND ") { "( ${renderNode(it, alias, params)} )" }
        is SqlPred.Or -> pred.parts.joinToString(" OR ") { "( ${renderNode(it, alias, params)} )" }
        is SqlPred.Not -> "NOT ( ${renderNode(pred.part, alias, params)} )"
        is SqlPred.Column -> {
            params += pred.param
            val op = when (pred.op) {
                CmpOp.EQ -> "="
                CmpOp.LT -> "<"
                CmpOp.LTE -> "<="
                CmpOp.GT -> ">"
                CmpOp.GTE -> ">="
                CmpOp.CONTAINS -> "LIKE"
            }
            val column = colSql(pred.col, alias)
            if (pred.op == CmpOp.CONTAINS) {
                val v = (pred.param as Param.Str).value
                params[params.size - 1] = Param.Str(v) // lower() применяется к обеим сторонам
                "lower($column) LIKE lower(?)"
            } else {
                "$column $op ?"
            }
        }
        is SqlPred.ColumnNull -> {
            val column = colSql(pred.col, alias)
            if (pred.negated) "$column IS NOT NULL" else "$column IS NULL"
        }
        is SqlPred.HasSubtasks -> {
            val e = "EXISTS (SELECT 1 FROM tasks c WHERE c.parent_id = $alias.id)"
            if (pred.negated) "NOT ( $e )" else e
        }
        is SqlPred.ListName -> {
            params += Param.Str(pred.name.lowercase())
            val e = "EXISTS (SELECT 1 FROM lists l WHERE l.id = $alias.list_id AND lower(l.name) = lower(?))"
            if (pred.negated) "NOT ( $e )" else e
        }
        is SqlPred.TagName -> {
            params += Param.Str(pred.name.lowercase())
            val e = "EXISTS (SELECT 1 FROM task_tags tt JOIN tags tg ON tg.id = tt.tag_id WHERE tt.task_id = $alias.id AND lower(tg.name) = lower(?))"
            if (pred.negated) "NOT ( $e )" else e
        }
        is SqlPred.LocationName -> {
            params += Param.Str(pred.name.lowercase())
            val e = "EXISTS (SELECT 1 FROM locations loc WHERE loc.id = $alias.location_id AND lower(loc.name) = lower(?))"
            if (pred.negated) "NOT ( $e )" else e
        }
        is SqlPred.SharedList -> {
            val e = "EXISTS (SELECT 1 FROM list_members lm WHERE lm.list_id = $alias.list_id AND lm.permission = 'view')"
            if (pred.negated) "NOT ( $e )" else e
        }
    }

    private fun colSql(col: TaskCol, alias: String): String = "$alias." + when (col) {
        TaskCol.NAME -> "name"
        TaskCol.PRIORITY -> "priority"
        TaskCol.DUE_AT -> "due_at"
        TaskCol.COMPLETED_AT -> "completed_at"
        TaskCol.RRULE -> "rrule"
        TaskCol.REPEAT_MODE -> "repeat_mode"
        TaskCol.LOCATION_ID -> "location_id"
        TaskCol.POSTPONED -> "postponed_count"
        TaskCol.ESTIMATE_MIN -> "estimate_min"
        TaskCol.CREATED_AT -> "created_at"
    }
}

/**
 * Трансляция AST → SQL-предикат. Границы окон (today 00:00 и т.п.) вычисляются
 * в поясе [ctx.timeZone] — так же, как в [QueryPredicate], для эквивалентности.
 */
object SqlBuilder {

    fun build(expr: QueryExpr, ctx: TaskEvalContext): SqlPred = when (expr) {
        is QueryExpr.And -> SqlPred.And(expr.children.map { build(it, ctx) })
        is QueryExpr.Or -> SqlPred.Or(expr.children.map { build(it, ctx) })
        is QueryExpr.Not -> SqlPred.Not(build(expr.child, ctx))
        is QueryExpr.Text -> SqlPred.Column(TaskCol.NAME, CmpOp.CONTAINS, Param.Str("%${expr.term}%"))
        is QueryExpr.Field -> buildField(expr, ctx)
    }

    private fun buildField(f: QueryExpr.Field, ctx: TaskEvalContext): SqlPred {
        val v = f.value
        return when (f.field) {
            QueryField.LIST -> SqlPred.ListName(vStr(v), false)
            QueryField.TAG -> SqlPred.TagName(vStr(v), false)
            QueryField.PRIORITY -> {
                val n = when (v) {
                    is FieldValue.Num -> v.value
                    is FieldValue.Str -> if (v.value.equals("none", true)) 0 else v.value.toIntOrNull()
                        ?: throw IllegalStateException("priority value: ${v.value}")
                    else -> throw IllegalStateException("priority value")
                }
                // none = NULL в БД: EQ по 0 транслируется в IS NULL
                if (f.op == CmpOp.EQ && n == 0) {
                    SqlPred.ColumnNull(TaskCol.PRIORITY, false)
                } else {
                    SqlPred.Column(TaskCol.PRIORITY, f.op, Param.Num(n.toLong()))
                }
            }
            QueryField.DUE -> buildDue(f, ctx)
            QueryField.DUE_BEFORE -> buildDueBefore(f, ctx)
            QueryField.DUE_WITHIN -> buildDueWithin(f, ctx)
            QueryField.COMPLETED -> {
                val b = boolOf(v)
                SqlPred.ColumnNull(TaskCol.COMPLETED_AT, negated = b)
            }
            QueryField.IS_REPEATING -> {
                val b = boolOf(v)
                if (b) {
                    SqlPred.And(
                        listOf(
                            SqlPred.ColumnNull(TaskCol.RRULE, true),
                            SqlPred.ColumnNull(TaskCol.REPEAT_MODE, true),
                        )
                    )
                } else {
                    SqlPred.Or(
                        listOf(
                            SqlPred.ColumnNull(TaskCol.RRULE, false),
                            SqlPred.ColumnNull(TaskCol.REPEAT_MODE, false),
                        )
                    )
                }
            }
            QueryField.HAS_SUBTASKS -> SqlPred.HasSubtasks(!boolOf(v))
            QueryField.LOCATION -> SqlPred.LocationName(vStr(v), false)
            QueryField.IS_SHARED -> SqlPred.SharedList(!boolOf(v))
            QueryField.ADDED_WITHIN -> {
                val dur = DurationParser.parse(vStr(v)) ?: throw IllegalStateException("addedWithin value: ${vStr(v)}")
                SqlPred.Column(
                    TaskCol.CREATED_AT,
                    CmpOp.GTE,
                    Param.Ts(Instant.fromEpochMilliseconds(ctx.now.toEpochMilliseconds() - dur.millis).toString()),
                )
            }
            QueryField.POSTPONED -> SqlPred.Column(
                TaskCol.POSTPONED, f.op, Param.Num(vNum(v).toLong()),
            )
            QueryField.TIME_ESTIMATE -> {
                val (op, minutes) = estimateOf(v) ?: throw IllegalStateException("timeEstimate value: $v")
                SqlPred.Column(TaskCol.ESTIMATE_MIN, op, Param.Num(minutes.toLong()))
            }
        }
    }

    private fun buildDue(f: QueryExpr.Field, ctx: TaskEvalContext): SqlPred {
        val s = vStr(f.value)
        return when (kwOf(s)) {
            DueKwSql.TODAY -> window(ctx, 0, 1).alsoNotCompleted()
            DueKwSql.TOMORROW -> window(ctx, 1, 2).alsoNotCompleted()
            DueKwSql.OVERDUE -> SqlPred.And(
                listOf(SqlPred.Column(TaskCol.DUE_AT, CmpOp.LT, dayStartParam(ctx, 0)), notCompleted())
            )
            DueKwSql.THIS_WEEK -> {
                val start = weekStartDate(ctx.today)
                weekWindow(ctx, start).alsoNotCompleted()
            }
            DueKwSql.NEXT_WEEK -> {
                val start = weekStartDate(ctx.today).plusDays(7)
                weekWindow(ctx, start).alsoNotCompleted()
            }
            null -> {
                groupFrom(s, ctx)
            }
        }
    }

    /** due:2026-10-20 — окно дня (date equality), без фильтра completed. */
    private fun groupFrom(s: String, ctx: TaskEvalContext): SqlPred {
        val d = parseIso(s) ?: throw IllegalStateException("due value: $s")
        return SqlPred.And(
            listOf(
                SqlPred.Column(TaskCol.DUE_AT, CmpOp.GTE, Param.Ts(dayStart(ctx, d).toString())),
                SqlPred.Column(TaskCol.DUE_AT, CmpOp.LT, Param.Ts(dayStart(ctx, d.plusDays(1)).toString())),
            )
        )
    }

    private fun buildDueBefore(f: QueryExpr.Field, ctx: TaskEvalContext): SqlPred {
        val s = vStr(f.value)
        val limit: LocalDate = when {
            s.equals("today", true) || s.equals("сегодня", true) -> ctx.today
            s.equals("tomorrow", true) || s.equals("завтра", true) -> ctx.today.plusDays(1)
            parseIso(s) != null -> parseIso(s)!!
            DurationParser.parse(s) != null -> ctx.today.plusDays(DurationParser.parse(s)!!.days.toLong())
            else -> throw IllegalStateException("dueBefore value: $s")
        }
        return SqlPred.Column(TaskCol.DUE_AT, CmpOp.LT, dayStartParam(ctx, daysFrom(ctx.today, limit)))
    }

    private fun buildDueWithin(f: QueryExpr.Field, ctx: TaskEvalContext): SqlPred {
        val s = vStr(f.value)
        val end: Long = if (kwOf(s) == DueKwSql.TODAY) {
            val endDate = ctx.today.plusDays(1)
            dayStart(ctx, endDate).toEpochMilliseconds()
        } else {
            val dur = DurationParser.parse(s) ?: throw IllegalStateException("dueWithin value: $s")
            ctx.now.toEpochMilliseconds() + dur.millis
        }
        return SqlPred.And(
            listOf(
                SqlPred.Column(TaskCol.DUE_AT, CmpOp.GTE, Param.Ts(ctx.now.toString())),
                SqlPred.Column(TaskCol.DUE_AT, CmpOp.LTE, Param.Ts(Instant.fromEpochMilliseconds(end).toString())),
            )
        )
    }

    // ---------- хелперы ----------

    private enum class DueKwSql { TODAY, TOMORROW, OVERDUE, THIS_WEEK, NEXT_WEEK }

    private fun kwOf(s: String): DueKwSql? = when (s.lowercase()) {
        "today", "сегодня" -> DueKwSql.TODAY
        "tomorrow", "завтра" -> DueKwSql.TOMORROW
        "overdue", "просрочено" -> DueKwSql.OVERDUE
        "thisweek", "this week", "на этой неделе" -> DueKwSql.THIS_WEEK
        "nextweek", "next week", "на следующей неделе" -> DueKwSql.NEXT_WEEK
        else -> null
    }

    private fun parseIso(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun dayStart(ctx: TaskEvalContext, date: LocalDate): Instant =
        LocalDateTime(date, LocalTime(0, 0, 0, 0)).toInstant(ctx.timeZone)

    private fun dayStartParam(ctx: TaskEvalContext, offsetDays: Int): Param =
        Param.Ts(dayStart(ctx, ctx.today.plusDays(offsetDays.toLong())).toString())

    private fun daysFrom(today: LocalDate, limit: LocalDate): Int =
        LimitDayDiff.days(today, limit)

    private fun weekStartDate(today: LocalDate): LocalDate =
        today.plusDays(-((today.dayOfWeek.isoDayNumber - 1).toLong()))

    private fun window(ctx: TaskEvalContext, fromDays: Int, toDays: Int): SqlPred = SqlPred.And(
        listOf(
            SqlPred.Column(TaskCol.DUE_AT, CmpOp.GTE, dayStartParam(ctx, fromDays)),
            SqlPred.Column(TaskCol.DUE_AT, CmpOp.LT, dayStartParam(ctx, toDays)),
        )
    )

    private fun weekWindow(ctx: TaskEvalContext, start: LocalDate): SqlPred = SqlPred.And(
        listOf(
            SqlPred.Column(TaskCol.DUE_AT, CmpOp.GTE, Param.Ts(dayStart(ctx, start).toString())),
            SqlPred.Column(TaskCol.DUE_AT, CmpOp.LT, Param.Ts(dayStart(ctx, start.plusDays(7)).toString())),
        )
    )

    private fun notCompleted(): SqlPred = SqlPred.ColumnNull(TaskCol.COMPLETED_AT, false)

    private fun SqlPred.alsoNotCompleted(): SqlPred = SqlPred.And(listOf(this, notCompleted()))

    private fun vStr(v: FieldValue): String = (v as? FieldValue.Str)?.value
        ?: throw IllegalStateException("Expected string value, got $v")

    private fun vNum(v: FieldValue): Int = (v as? FieldValue.Num)?.value
        ?: throw IllegalStateException("Expected numeric value, got $v")

    private fun boolOf(v: FieldValue): Boolean = when (v) {
        is FieldValue.Bool -> v.value
        is FieldValue.Str -> when (v.value.lowercase()) {
            "true", "yes", "да", "1" -> true
            "false", "no", "нет", "0" -> false
            else -> throw IllegalStateException("boolean value: ${v.value}")
        }
        else -> throw IllegalStateException("boolean value: $v")
    }

    private fun estimateOf(v: FieldValue): Pair<CmpOp, Int>? {
        val s = when (v) {
            is FieldValue.Str -> v.value.trim()
            is FieldValue.Num -> "${v.value} minutes"
            else -> return null
        }
        var op = CmpOp.EQ
        var rest = s
        for ((prefix, o) in listOf(">=" to CmpOp.GTE, "<=" to CmpOp.LTE, ">" to CmpOp.GT, "<" to CmpOp.LT, "=" to CmpOp.EQ)) {
            if (s.startsWith(prefix)) {
                op = o
                rest = s.substring(prefix.length).trim()
                break
            }
        }
        val dur = DurationParser.parse(rest) ?: return null
        val minutes = when (dur.unit) {
            DurationUnit.HOUR -> dur.amount * 60
            DurationUnit.MINUTE -> dur.amount
            else -> return null
        }
        return op to minutes
    }
}

/** Разница в днях (today..limit), для dueBefore-параметров. */
private object LimitDayDiff {
    fun days(from: LocalDate, to: LocalDate): Int {
        var d = from
        var n = 0
        while (d < to) { d = d.plusDays(1); n++ }
        while (d > to) { d = d.plusDays(-1); n-- }
        return n
    }
}