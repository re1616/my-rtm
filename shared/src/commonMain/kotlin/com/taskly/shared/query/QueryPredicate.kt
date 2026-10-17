package com.taskly.shared.query

import com.taskly.shared.domain.model.Task
import com.taskly.shared.domain.model.Uuid
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Единицы длительности в значениях «1 week», «30 days», «2 hours», «1 month». */
enum class DurationUnit { DAY, WEEK, HOUR, MINUTE, MONTH }

data class DurationSpec(val amount: Int, val unit: DurationUnit) {
    /** В миллисекундах (для Instant-сравнений); месяц ≈ 30 дней. */
    val millis: Long
        get() = when (unit) {
            DurationUnit.DAY -> amount * 86_400_000L
            DurationUnit.WEEK -> amount * 7 * 86_400_000L
            DurationUnit.HOUR -> amount * 3_600_000L
            DurationUnit.MINUTE -> amount * 60_000L
            DurationUnit.MONTH -> amount * 30 * 86_400_000L
        }

    /** В днях (для датовых сравнений; час/минута < дня → 0). */
    val days: Int
        get() = when (unit) {
            DurationUnit.DAY -> amount
            DurationUnit.WEEK -> amount * 7
            DurationUnit.HOUR, DurationUnit.MINUTE -> 0
            DurationUnit.MONTH -> amount * 30
        }
}

/** Разбор длительности: "1 week", "2 weeks", "30 days", "4 hours", "45 minutes", "1 month", RU-формы. */
object DurationParser {

    private val regex = Regex(
        """^(\d+)\s*(d|day|days|w|week|weeks|h|hr|hour|hours|m|min|minute|minutes|month|months|день|дня|дней|дн|неделю|неделя|недели|недель|нед|часов|час|часа|минут|минуты|минута|мин|месяц|месяца|месяцев|ч|м)$"""
    )

    fun parse(raw: String): DurationSpec? {
        val w = raw.trim().lowercase().replace(Regex("""\s+"""), " ")
        val m = regex.matchEntire(w) ?: return null
        val amount = m.groupValues[1].toInt().takeIf { it > 0 } ?: return null
        val unit = m.groupValues[2]
        return DurationSpec(amount, when (unit) {
            "d", "day", "days", "день", "дня", "дней", "дн" -> DurationUnit.DAY
            "w", "week", "weeks", "неделю", "неделя", "недели", "недель", "нед" -> DurationUnit.WEEK
            "h", "hr", "hour", "hours", "часов", "час", "часа", "ч" -> DurationUnit.HOUR
            "month", "months", "месяц", "месяца", "месяцев" -> DurationUnit.MONTH
            else -> DurationUnit.MINUTE
        })
    }
}

/**
 * Контекст исполнения предиката над локальной базой: справочники имён
 * (списки/теги/локации), множества общих списков и задач-родителей, «сейчас» и пояс.
 */
data class TaskEvalContext(
    val now: Instant,
    val timeZone: TimeZone = TimeZone.of("Europe/Moscow"),
    val listNames: Map<Uuid, String> = emptyMap(),
    val tagNamesById: Map<Uuid, List<String>> = emptyMap(),
    val locationNames: Map<Uuid, String> = emptyMap(),
    val sharedListIds: Set<Uuid> = emptySet(),
    /** id задач, у которых есть подзадачи. */
    val parentTaskIds: Set<Uuid> = emptySet(),
) {
    val today: LocalDate get() = now.toLocalDateTime(timeZone).date
}

/** Ключевые слова поля due / dueBefore. */
private enum class DueKw { TODAY, TOMORROW, OVERDUE, THIS_WEEK, NEXT_WEEK }

/**
 * Интерпретатор AST → предикат над задачей (клиент, локальная БД).
 * Семантика полей — SPEC F-3; детали зафиксированы в KDoc каждого условия.
 */
object QueryPredicate {

    fun build(expr: QueryExpr): (Task, TaskEvalContext) -> Boolean = { task, ctx -> eval(expr, task, ctx) }

    fun eval(expr: QueryExpr, task: Task, ctx: TaskEvalContext): Boolean = when (expr) {
        is QueryExpr.And -> expr.children.all { eval(it, task, ctx) }
        is QueryExpr.Or -> expr.children.any { eval(it, task, ctx) }
        is QueryExpr.Not -> !eval(expr.child, task, ctx)
        is QueryExpr.Text -> task.name.contains(expr.term, ignoreCase = true)
        is QueryExpr.Field -> evalField(expr, task, ctx)
    }

    private fun evalField(f: QueryExpr.Field, task: Task, ctx: TaskEvalContext): Boolean {
        val value = f.value
        return when (f.field) {
            QueryField.LIST -> {
                val v = value as? FieldValue.Str ?: return false
                ctx.listNames[task.listId]?.equals(v.value, ignoreCase = true) ?: false
            }
            QueryField.TAG -> {
                val v = value as? FieldValue.Str ?: return false
                (ctx.tagNamesById[task.id] ?: task.tagNames).any { it.equals(v.value, ignoreCase = true) }
            }
            QueryField.PRIORITY -> {
                val level = task.priority.level
                val n = when (value) {
                    is FieldValue.Num -> value.value
                    is FieldValue.Str -> if (value.value.equals("none", true)) 0 else value.value.toIntOrNull() ?: return false
                    else -> return false
                }
                // none = priority IS NULL в SQL: EQ по 0 матчит только отсутствие приоритета;
                // диапазоны «none» не включают (NULL-семантика SQL)
                if (level == null) return f.op == CmpOp.EQ && n == 0
                return cmpInt(level, n, f.op)
            }
            QueryField.DUE -> evalDue(f, task, ctx)
            QueryField.DUE_BEFORE -> evalDueBefore(f, task, ctx)
            QueryField.DUE_WITHIN -> {
                val due = task.dueAt ?: return false
                val end: Long = when (value) {
                    is FieldValue.Str -> {
                        val kw = dueKw(value.value)
                        if (kw == DueKw.TODAY) endOfDay(ctx, 0)
                        else DurationParser.parse(value.value)?.let { ctx.now.toEpochMilliseconds() + it.millis }
                            ?: return false
                    }
                    else -> return false
                }
                due.toEpochMilliseconds() >= ctx.now.toEpochMilliseconds() && due.toEpochMilliseconds() <= end
            }
            QueryField.COMPLETED -> {
                val b = boolValue(value) ?: return false
                (task.completedAt != null) == b
            }
            QueryField.IS_REPEATING -> {
                val b = boolValue(value) ?: return false
                (task.rrule != null) == b
            }
            QueryField.HAS_SUBTASKS -> {
                val b = boolValue(value) ?: return false
                (task.id in ctx.parentTaskIds) == b
            }
            QueryField.LOCATION -> {
                val v = value as? FieldValue.Str ?: return false
                task.locationId?.let { ctx.locationNames[it] }?.equals(v.value, ignoreCase = true) ?: false
            }
            QueryField.IS_SHARED -> {
                val b = boolValue(value) ?: return false
                (task.listId != null && task.listId in ctx.sharedListIds) == b
            }
            QueryField.ADDED_WITHIN -> {
                val v = value as? FieldValue.Str ?: return false
                val dur = DurationParser.parse(v.value) ?: return false
                task.createdAt.toEpochMilliseconds() >= ctx.now.toEpochMilliseconds() - dur.millis
            }
            QueryField.POSTPONED -> cmpInt(task.postponedCount, intValue(value) ?: return false, f.op)
            QueryField.TIME_ESTIMATE -> {
                val estimate = task.estimateMin ?: return false
                val (op, minutes) = estimateCondition(value) ?: return false
                cmpInt(estimate, minutes, op)
            }
        }
    }

    // ---------- due / dueBefore ----------

    private fun evalDue(f: QueryExpr.Field, task: Task, ctx: TaskEvalContext): Boolean {
        val due = task.dueAt ?: return false
        val dueDate = due.toLocalDateTime(ctx.timeZone).date
        val value = f.value as? FieldValue.Str ?: return false
        dueKw(value.value)?.let { kw ->
            return when (kw) {
                DueKw.TODAY -> !task.isCompleted && dueDate == ctx.today
                DueKw.TOMORROW -> !task.isCompleted && dueDate == ctx.today.plusDays(1)
                DueKw.OVERDUE -> !task.isCompleted && dueDate < ctx.today
                DueKw.THIS_WEEK -> {
                    !task.isCompleted && dueDate >= weekStart(ctx.today) && dueDate < weekStart(ctx.today).plusDays(7)
                }
                DueKw.NEXT_WEEK -> {
                    !task.isCompleted && dueDate >= weekStart(ctx.today).plusDays(7) &&
                        dueDate < weekStart(ctx.today).plusDays(14)
                }
            }
        }
        // explicit date: equality (без фильтра completed — сырое условие)
        val d = parseIsoDate(value.value) ?: return false
        return dueDate == d
    }

    private fun evalDueBefore(f: QueryExpr.Field, task: Task, ctx: TaskEvalContext): Boolean {
        val due = task.dueAt ?: return false
        val dueDate = due.toLocalDateTime(ctx.timeZone).date
        val value = f.value as? FieldValue.Str ?: return false
        val limit: LocalDate = when {
            value.value.equals("today", true) || value.value.equals("сегодня", true) -> ctx.today
            value.value.equals("tomorrow", true) || value.value.equals("завтра", true) -> ctx.today.plusDays(1)
            parseIsoDate(value.value) != null -> parseIsoDate(value.value)!!
            DurationParser.parse(value.value) != null -> ctx.today.plusDays(DurationParser.parse(value.value)!!.days.toLong())
            else -> return false
        }
        return dueDate < limit
    }

    // ---------- Хелперы ----------

    private fun parseIsoDate(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun weekStart(today: LocalDate): LocalDate =
        today.plusDays(-((today.dayOfWeek.isoDayNumber - 1).toLong()))

    private fun endOfDay(ctx: TaskEvalContext, daysFromToday: Int): Long {
        val d = ctx.today.plusDays(daysFromToday.toLong()).plusDays(1)
        return kotlinx.datetime.LocalDateTime(d, kotlinx.datetime.LocalTime(0, 0, 0, 0)).toInstant(ctx.timeZone).toEpochMilliseconds()
    }

    private fun dueKw(s: String): DueKw? = when (s.lowercase()) {
        "today", "сегодня" -> DueKw.TODAY
        "tomorrow", "завтра" -> DueKw.TOMORROW
        "overdue", "просрочено" -> DueKw.OVERDUE
        "thisweek", "this week", "на этой неделе" -> DueKw.THIS_WEEK
        "nextweek", "next week", "на следующей неделе" -> DueKw.NEXT_WEEK
        else -> null
    }

    private fun boolValue(v: FieldValue): Boolean? = when (v) {
        is FieldValue.Bool -> v.value
        is FieldValue.Str -> when (v.value.lowercase()) {
            "true", "yes", "да", "1" -> true
            "false", "no", "нет", "0" -> false
            else -> null
        }
        else -> null
    }

    private fun intValue(v: FieldValue): Int? = when (v) {
        is FieldValue.Num -> v.value
        is FieldValue.Str -> v.value.toIntOrNull()
        else -> null
    }

    private fun cmpInt(actual: Int, expected: Int, op: CmpOp): Boolean = when (op) {
        CmpOp.EQ -> actual == expected
        CmpOp.LT -> actual < expected
        CmpOp.LTE -> actual <= expected
        CmpOp.GT -> actual > expected
        CmpOp.GTE -> actual >= expected
        CmpOp.CONTAINS -> false // не применимо к числам
    }

    /** timeEstimate: "30 minutes" (EQ), "<1 hour", ">2 hours", "=45m". */
    private fun estimateCondition(v: FieldValue): Pair<CmpOp, Int>? {
        val s = when (v) {
            is FieldValue.Str -> v.value.trim()
            is FieldValue.Num -> v.value.toString() + " minutes"
            else -> return null
        }
        var op = CmpOp.EQ
        var rest = s
        for ((prefix, o) in listOf(">=" to CmpOp.GTE, "<=" to CmpOp.LTE, ">" to CmpOp.GT, "<" to CmpOp.LT, "=" to CmpOp.EQ)) {
            if (s.startsWith(prefix)) { op = o; rest = s.substring(prefix.length).trim(); break }
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