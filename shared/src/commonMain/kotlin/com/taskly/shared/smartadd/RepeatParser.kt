package com.taskly.shared.smartadd

import com.taskly.shared.domain.model.RecurrenceFreq
import com.taskly.shared.domain.model.RepeatMode
import kotlinx.datetime.DayOfWeek

/**
 * Разобранное правило повтора из Smart Add: частота + интервал + режим every/after
 * + опциональные дни недели. rrule — каноническая RRULE-строка для хранения.
 */
data class ParsedRepeat(
    val freq: RecurrenceFreq,
    val interval: Int,
    val mode: RepeatMode,
    val byDay: List<DayOfWeek> = emptyList(),
    val rrule: String,
)

/**
 * Парсер повторов Smart Add (SPEC F-2, F-4). RU + EN:
 * «каждую неделю», «каждые 2 дня», «еженедельно», «каждый понедельник»,
 * «после 2 недель», «every week», «every 2 days», «after 2 weeks», «on mondays».
 */
object RepeatParser {

    private const val DAY_ABBR = "MO|TU|WE|TH|FR|SA|SU"

    private val weekdaysRu = mapOf(
        "пн" to DayOfWeek.MONDAY, "понедельник" to DayOfWeek.MONDAY,
        "вт" to DayOfWeek.TUESDAY, "вторник" to DayOfWeek.TUESDAY,
        "ср" to DayOfWeek.WEDNESDAY, "среда" to DayOfWeek.WEDNESDAY,
        "чт" to DayOfWeek.THURSDAY, "четверг" to DayOfWeek.THURSDAY,
        "пт" to DayOfWeek.FRIDAY, "пятница" to DayOfWeek.FRIDAY,
        "сб" to DayOfWeek.SATURDAY, "суббота" to DayOfWeek.SATURDAY,
        "вс" to DayOfWeek.SUNDAY, "воскресенье" to DayOfWeek.SUNDAY,
    )
    private val weekdaysEn = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )
    // Винительный падеж: «каждую пятницу» / «каждую среду»
    private val weekdaysRuAcc = mapOf(
        "пятницу" to DayOfWeek.FRIDAY, "среду" to DayOfWeek.WEDNESDAY,
        "субботу" to DayOfWeek.SATURDAY, "неделю" to null, // «каждую неделю» — не день
    )
    // Множественное: «по понедельникам», «on mondays»
    private val weekdaysRuPl = mapOf(
        "понедельникам" to DayOfWeek.MONDAY, "вторникам" to DayOfWeek.TUESDAY,
        "средам" to DayOfWeek.WEDNESDAY, "четвергам" to DayOfWeek.THURSDAY,
        "пятницам" to DayOfWeek.FRIDAY, "субботам" to DayOfWeek.SATURDAY,
        "воскресеньям" to DayOfWeek.SUNDAY,
    )
    private val weekdaysEnPl = mapOf(
        "mondays" to DayOfWeek.MONDAY, "tuesdays" to DayOfWeek.TUESDAY,
        "wednesdays" to DayOfWeek.WEDNESDAY, "thursdays" to DayOfWeek.THURSDAY,
        "fridays" to DayOfWeek.FRIDAY, "saturdays" to DayOfWeek.SATURDAY,
        "sundays" to DayOfWeek.SUNDAY,
    )

    fun parse(phrase: String): ParsedRepeat? {
        val w = phrase.trim().lowercase().replace(Regex("""\s+"""), " ")
        if (w.isEmpty()) return null
        return parseEvery(w) ?: parseAfter(w) ?: parseWeekday(w)
    }

    private fun rrule(freq: RecurrenceFreq, interval: Int, byDay: List<DayOfWeek> = emptyList()): String {
        val parts = mutableListOf("FREQ=${freq.name}")
        if (interval > 1) parts.add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) parts.add("BYDAY=" + byDay.joinToString(",") {
            when (it) {
                DayOfWeek.MONDAY -> "MO"; DayOfWeek.TUESDAY -> "TU"; DayOfWeek.WEDNESDAY -> "WE"
                DayOfWeek.THURSDAY -> "TH"; DayOfWeek.FRIDAY -> "FR"; DayOfWeek.SATURDAY -> "SA"
                DayOfWeek.SUNDAY -> "SU"
            }
        })
        return parts.joinToString(";")
    }

    private fun parseEvery(w: String): ParsedRepeat? {
        // RU: фиксированные
        when (w) {
            "ежедневно", "каждый день", "каждодневно" ->
                return ParsedRepeat(RecurrenceFreq.DAILY, 1, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.DAILY, 1))
            "еженедельно", "каждую неделю" ->
                return ParsedRepeat(RecurrenceFreq.WEEKLY, 1, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.WEEKLY, 1))
            "ежемесячно", "каждый месяц" ->
                return ParsedRepeat(RecurrenceFreq.MONTHLY, 1, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.MONTHLY, 1))
            "ежегодно", "каждый год" ->
                return ParsedRepeat(RecurrenceFreq.YEARLY, 1, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.YEARLY, 1))
        }
        // EN: фиксированные
        when (w) {
            "daily", "every day", "each day" ->
                return ParsedRepeat(RecurrenceFreq.DAILY, 1, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.DAILY, 1))
            "weekly", "every week", "each week", "everyweek" ->
                return ParsedRepeat(RecurrenceFreq.WEEKLY, 1, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.WEEKLY, 1))
            "monthly", "every month", "each month" ->
                return ParsedRepeat(RecurrenceFreq.MONTHLY, 1, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.MONTHLY, 1))
            "yearly", "annually", "every year", "each year" ->
                return ParsedRepeat(RecurrenceFreq.YEARLY, 1, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.YEARLY, 1))
        }
        // RU: «каждые N ...»
        val ruNum = Regex("""^каждые\s+(\d+)\s+(дней|дня|день|дни|недель|недели|неделю|месяцев|месяца|месяц|лет|года|год)$""").matchEntire(w)
        if (ruNum != null) {
            val (n, unit) = ruNum.destructured
            val freq = when {
                unit in setOf("дней", "дня", "день", "дни") -> RecurrenceFreq.DAILY
                unit in setOf("недель", "недели", "неделю") -> RecurrenceFreq.WEEKLY
                unit in setOf("месяцев", "месяца", "месяц") -> RecurrenceFreq.MONTHLY
                else -> RecurrenceFreq.YEARLY
            }
            return ParsedRepeat(freq, n.toInt(), RepeatMode.EVERY, rrule = rrule(freq, n.toInt()))
        }
        // RU: «каждый N-й день» → каждые N дней
        val ruOrd = Regex("""^кажд\w+\s+(\d+)-[йя]+\s+(день|дней)$""").matchEntire(w)
        if (ruOrd != null) {
            val n = ruOrd.groupValues[1].toInt()
            return ParsedRepeat(RecurrenceFreq.DAILY, n, RepeatMode.EVERY, rrule = rrule(RecurrenceFreq.DAILY, n))
        }
        // EN: «every N days/weeks/months/years»
        val enNum = Regex("""^every\s+(\d+)\s*(days?|weeks?|months?|years?)$""").matchEntire(w)
        if (enNum != null) {
            val (n, unit) = enNum.destructured
            val freq = when {
                unit.startsWith("day") -> RecurrenceFreq.DAILY
                unit.startsWith("week") -> RecurrenceFreq.WEEKLY
                unit.startsWith("month") -> RecurrenceFreq.MONTHLY
                else -> RecurrenceFreq.YEARLY
            }
            return ParsedRepeat(freq, n.toInt(), RepeatMode.EVERY, rrule = rrule(freq, n.toInt()))
        }
        // EN: «every other day / every other week»
        val enOther = Regex("""^every\s+other\s+(day|week|month|year)$""").matchEntire(w)
        if (enOther != null) {
            val unit = enOther.groupValues[1]
            val freq = when (unit) {
                "day" -> RecurrenceFreq.DAILY; "week" -> RecurrenceFreq.WEEKLY
                "month" -> RecurrenceFreq.MONTHLY; else -> RecurrenceFreq.YEARLY
            }
            return ParsedRepeat(freq, 2, RepeatMode.EVERY, rrule = rrule(freq, 2))
        }
        return null
    }

    private fun parseAfter(w: String): ParsedRepeat? {
        // RU: «после N единиц», «после недели»
        val ruNum = Regex("""^после\s+(\d+)\s+(дней|дня|день|недель|недели|неделю|месяцев|месяца|месяц|лет|года|год)$""").matchEntire(w)
        if (ruNum != null) {
            val (n, unit) = ruNum.destructured
            val freq = when {
                unit in setOf("дней", "дня", "день") -> RecurrenceFreq.DAILY
                unit in setOf("недель", "недели", "неделю") -> RecurrenceFreq.WEEKLY
                unit in setOf("месяцев", "месяца", "месяц") -> RecurrenceFreq.MONTHLY
                else -> RecurrenceFreq.YEARLY
            }
            return ParsedRepeat(freq, n.toInt(), RepeatMode.AFTER, rrule = rrule(freq, n.toInt()))
        }
        val ruSingular = Regex("""^после\s+(дня|недели|месяца|года)$""").matchEntire(w)
        if (ruSingular != null) {
            val unit = ruSingular.groupValues[1]
            val freq = when (unit) {
                "дня" -> RecurrenceFreq.DAILY; "недели" -> RecurrenceFreq.WEEKLY
                "месяца" -> RecurrenceFreq.MONTHLY; else -> RecurrenceFreq.YEARLY
            }
            return ParsedRepeat(freq, 1, RepeatMode.AFTER, rrule = rrule(freq, 1))
        }
        // EN: «after N days/weeks/months/years», «after a week»
        val enNum = Regex("""^after\s+(\d+)\s*(days?|weeks?|months?|years?)$""").matchEntire(w)
        if (enNum != null) {
            val (n, unit) = enNum.destructured
            val freq = when {
                unit.startsWith("day") -> RecurrenceFreq.DAILY
                unit.startsWith("week") -> RecurrenceFreq.WEEKLY
                unit.startsWith("month") -> RecurrenceFreq.MONTHLY
                else -> RecurrenceFreq.YEARLY
            }
            return ParsedRepeat(freq, n.toInt(), RepeatMode.AFTER, rrule = rrule(freq, n.toInt()))
        }
        val enA = Regex("""^after\s+(a|an|one)\s+(day|week|month|year)$""").matchEntire(w)
        if (enA != null) {
            val unit = enA.groupValues[2]
            val freq = when (unit) {
                "day" -> RecurrenceFreq.DAILY; "week" -> RecurrenceFreq.WEEKLY
                "month" -> RecurrenceFreq.MONTHLY; else -> RecurrenceFreq.YEARLY
            }
            return ParsedRepeat(freq, 1, RepeatMode.AFTER, rrule = rrule(freq, 1))
        }
        return null
    }

    private fun parseWeekday(w: String): ParsedRepeat? {
        // RU: «каждый понедельник», «каждое воскресенье»
        val ruNom = Regex("""^кажд(?:ый|ое)\s+([а-яё]+)$""").matchEntire(w)
        if (ruNom != null) {
            weekdaysRu[ruNom.groupValues[1]]?.let { dow ->
                return ParsedRepeat(RecurrenceFreq.WEEKLY, 1, RepeatMode.EVERY, listOf(dow), rrule(RecurrenceFreq.WEEKLY, 1, listOf(dow)))
            }
        }
        val ruAcc = Regex("""^каждую\s+([а-яё]+)$""").matchEntire(w)
        if (ruAcc != null) {
            val d = weekdaysRuAcc[ruAcc.groupValues[1]]
            if (d != null) {
                return ParsedRepeat(RecurrenceFreq.WEEKLY, 1, RepeatMode.EVERY, listOf(d), rrule(RecurrenceFreq.WEEKLY, 1, listOf(d)))
            }
        }
        val ruPl = Regex("""^по\s+([а-яё]+)$""").matchEntire(w)
        if (ruPl != null) {
            weekdaysRuPl[ruPl.groupValues[1]]?.let { dow ->
                return ParsedRepeat(RecurrenceFreq.WEEKLY, 1, RepeatMode.EVERY, listOf(dow), rrule(RecurrenceFreq.WEEKLY, 1, listOf(dow)))
            }
        }
        // EN: «every monday», «on mondays», «each monday»
        val enEvery = Regex("""^(every|each)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$""").matchEntire(w)
        if (enEvery != null) {
            weekdaysEn[enEvery.groupValues[2]]?.let { dow ->
                return ParsedRepeat(RecurrenceFreq.WEEKLY, 1, RepeatMode.EVERY, listOf(dow), rrule(RecurrenceFreq.WEEKLY, 1, listOf(dow)))
            }
        }
        val enOn = Regex("""^on\s+(mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays)$""").matchEntire(w)
        if (enOn != null) {
            weekdaysEnPl[enOn.groupValues[1]]?.let { dow ->
                return ParsedRepeat(RecurrenceFreq.WEEKLY, 1, RepeatMode.EVERY, listOf(dow), rrule(RecurrenceFreq.WEEKLY, 1, listOf(dow)))
            }
        }
        return null
    }
}