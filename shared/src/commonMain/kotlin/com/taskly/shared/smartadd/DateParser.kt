package com.taskly.shared.smartadd

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant

/** Распознанная дата срока/начала: дата + (опц.) время, инстант в поясе [tz]. */
data class ParsedDue(
    val date: LocalDate,
    val time: LocalTime?,
    val hasTime: Boolean,
    val instant: Instant,
) {
    companion object {
        fun of(date: LocalDate, time: LocalTime?, hasTime: Boolean, tz: TimeZone): ParsedDue {
            val dt = if (hasTime && time != null) {
                LocalDateTime(date, time)
            } else {
                LocalDateTime(date, LocalTime(0, 0, 0, 0))
            }
            return ParsedDue(date, time, hasTime, dt.toInstant(tz))
        }
    }
}

/**
 * Детерминированный rule-based парсер дат Smart Add (SPEC F-2), RU + EN:
 * «сегодня/завтра/послезавтра», «через 3 дня», дни недели, «25 дек», ISO,
 * время 12/24-часовое, «9 утра/вечера». Привязка к [today] — для детерминизма тестов.
 */
object DateParser {

    private val monthsRu = mapOf(
        "янв" to 1, "январь" to 1, "января" to 1,
        "фев" to 2, "февраль" to 2, "февраля" to 2,
        "мар" to 3, "март" to 3, "марта" to 3,
        "апр" to 4, "апрель" to 4, "апреля" to 4,
        "май" to 5, "мая" to 5,
        "июн" to 6, "июнь" to 6, "июня" to 6,
        "июл" to 7, "июль" to 7, "июля" to 7,
        "авг" to 8, "август" to 8, "августа" to 8,
        "сен" to 9, "сентябрь" to 9, "сентября" to 9,
        "окт" to 10, "октябрь" to 10, "октября" to 10,
        "ноя" to 11, "ноябрь" to 11, "ноября" to 11,
        "дек" to 12, "декабрь" to 12, "декабря" to 12,
    )
    private val monthsEn = mapOf(
        "jan" to 1, "january" to 1,
        "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5,
        "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12,
    )

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
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )

    private fun monthNum(word: String): Int? {
        monthsRu[word]?.let { return it }
        monthsEn[word]?.let { return it }
        return null
    }

    private fun weekdayOf(word: String): DayOfWeek? {
        weekdaysRu[word]?.let { return it }
        weekdaysEn[word]?.let { return it }
        return null
    }

    /** Разбор времени: "17:00"/"17:00:00"/"5pm"/"5:30pm"/"9 утра"/"9 вечера". null — не время. */
    private fun parseTimeToken(s: String): LocalTime? {
        val t24 = Regex("""^([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?$""").matchEntire(s)
        if (t24 != null) {
            val (h, m, sec) = t24.destructured
            return LocalTime(h.toInt(), m.toInt(), if (sec.isEmpty()) 0 else sec.toInt())
        }
        val t12 = Regex("""^(\d{1,2})(?::([0-5]\d))?\s*(am|pm)$""").matchEntire(s)
        if (t12 != null) {
            var (h, m, ap) = t12.destructured
            var hour = h.toInt() % 12
            if (ap == "pm") hour += 12
            return LocalTime(hour, if (m.isEmpty()) 0 else m.toInt(), 0, 0)
        }
        val tRu = Regex("""^(\d{1,2})\s*(утра|вечера|дня|ночи)$""").matchEntire(s)
        if (tRu != null) {
            val (h, period) = tRu.destructured
            val hour = h.toInt()
            return when (period) {
                "вечера" -> LocalTime(if (hour < 12) hour + 12 else hour, 0, 0, 0)
                "ночи" -> LocalTime(if (hour == 12) 0 else hour, 0, 0, 0)
                else -> LocalTime(hour, 0, 0, 0)
            }
        }
        return null
    }

    /** Убирает префикс «в 17:00» / «at 5pm», оставляя время. */
    private fun stripTimePrefix(s: String): String {
        if (s.length > 3 && s.startsWith("в ")) return s.substring(2)
        if (s.length > 4 && s.startsWith("at ")) return s.substring(3)
        return s
    }

    /**
     * Пытается распознать ВСЮ фразу как дату(±время). null если не дата.
     * Грамматика: [дата] [время] | [время]; время — последнее слово или
     * два слова («9 утра»); «в 17:00» в середине фразы нормализуется.
     */
    fun parseDatePhrase(phrase: String, today: LocalDate, tz: TimeZone): ParsedDue? {
        val norm = phrase.trim().replace(Regex("""\s+"""), " ").lowercase()
        if (norm.isEmpty()) return null
        val s = stripTimePrefix(norm).replace(Regex("""\s+в\s+"""), " ")

        parseTimeToken(s)?.let { return ParsedDue.of(today, it, true, tz) }

        val words = s.split(" ")
        var time: LocalTime? = null
        var datePart: String? = null
        if (words.size >= 2) {
            val two = parseTimeToken(words[words.size - 2] + " " + words[words.size - 1])
            if (two != null) {
                time = two
                datePart = words.dropLast(2).joinToString(" ")
            }
        }
        if (time == null && words.size >= 2) {
            val one = parseTimeToken(words.last())
            if (one != null) {
                time = one
                datePart = words.dropLast(1).joinToString(" ")
            }
        }
        if (time != null) {
            val d = if (datePart.isNullOrEmpty()) today else parseDatePart(datePart!!, today)
            if (d != null) return ParsedDue.of(d, time, true, tz)
        }

        val d = parseDatePart(s, today)
        if (d != null) return ParsedDue.of(d, null, false, tz)
        return null
    }

    /** Разбор чистой даты (без времени). */
    fun parseDatePart(s: String, today: LocalDate): LocalDate? {
        var w = s.trim().lowercase()
        if (w.isEmpty()) return null

        // Абсолютные слова
        when (w) {
            "сегодня", "today" -> return today
            "завтра", "tomorrow" -> return dayPlus(today, 1)
            "послезавтра", "day after tomorrow", "the day after tomorrow", "overmorrow" -> return dayPlus(today, 2)
        }

        // Относительные: «через ...» / «in ...» / «next ...»
        relativeDate(w, today)?.let { return it }

        // Дни недели
        weekdayOf(w)?.let { dow -> return nextWeekday(today, dow) }

        // Месяц-день словами: «25 дек», «декабря 25», «dec 25», «25 december» [+ год]
        monthDayDate(w, today)?.let { return it }

        // ISO / числовые форматы
        val iso = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(w)
        if (iso != null) {
            val (y, m, d) = iso.destructured
            return safeDate(y.toInt(), m.toInt(), d.toInt())
        }
        val dot = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})$""").matchEntire(w)
        if (dot != null) {
            val (d, m, y) = dot.destructured
            return safeDate(y.toInt(), m.toInt(), d.toInt())
        }
        val dash = Regex("""^(\d{1,2})-(\d{1,2})-(\d{4})$""").matchEntire(w)
        if (dash != null) {
            val (d, m, y) = dash.destructured
            return safeDate(y.toInt(), m.toInt(), d.toInt())
        }
        return null
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate(y, m, d)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun relativeDate(w: String, today: LocalDate): LocalDate? {
        // RU: «через N единиц», «через день/неделю/месяц/год»
        val ruNum = Regex("""^через\s+(\d+)\s+(день|дни|дня|дней|дн|неделю|недели|недель|нед|месяц|месяца|месяцев|мес|год|года|лет)$""").matchEntire(w)
        if (ruNum != null) {
            val (n, unit) = ruNum.destructured
            return shift(today, n.toInt(), unit)
        }
        val ruSingular = Regex("""^через\s+(день|неделю|месяц|год)$""").matchEntire(w)
        if (ruSingular != null) {
            return shift(today, 1, ruSingular.groupValues[1])
        }
        // RU: «через час / 3 часа» — только время как дата (сегодня)
        val ruHour = Regex("""^через\s+(\d+)?\s*час(а|ов)?$""").matchEntire(w)
        if (ruHour != null) return today

        // EN: «in N days/weeks/months/years», «in a day», «next week»
        val enNum = Regex("""^in\s+(\d+)\s*(days?|weeks?|months?|years?)$""").matchEntire(w)
        if (enNum != null) {
            val (n, unit) = enNum.destructured
            return shift(today, n.toInt(), unit)
        }
        val enA = Regex("""^in\s+(a|an|one)\s+(day|week|month|year)$""").matchEntire(w)
        if (enA != null) return shift(today, 1, enA.groupValues[2])
        val nextKw = Regex("""^next\s+(week|month|year)$""").matchEntire(w)
        if (nextKw != null) return shift(today, 1, nextKw.groupValues[1])
        return null
    }

    private fun shift(from: LocalDate, n: Int, unit: String): LocalDate = when {
        unit in setOf("день", "дни", "дня", "дней", "дн", "day", "days") -> dayPlus(from, n.toLong())
        unit in setOf("неделю", "недели", "недель", "нед", "week", "weeks") -> dayPlus(from, (n * 7).toLong())
        unit in setOf("месяц", "месяца", "месяцев", "мес", "month", "months") -> monthPlus(from, n)
        else -> yearPlus(from, n) // год/года/лет/year(s)
    }

    private fun dayPlus(d: LocalDate, n: Long): LocalDate =
        kotlinx.datetime.LocalDate.fromEpochDays(d.toEpochDays() + n)

    private fun monthPlus(d: LocalDate, n: Int): LocalDate {
        val total = d.year * 12 + (d.monthNumber - 1) + n
        var y = total / 12
        var m = total % 12
        if (m < 0) { m += 12; y -= 1 }
        val monthNum = m + 1
        val last = daysInMonth(y, monthNum)
        return LocalDate(y, monthNum, minOf(d.dayOfMonth, last))
    }

    private fun yearPlus(d: LocalDate, n: Int): LocalDate {
        val y = d.year + n
        val m = d.monthNumber
        val last = daysInMonth(y, m)
        return LocalDate(y, m, minOf(d.dayOfMonth, last))
    }

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        else -> 30
    }

    /** День недели: если сегодня — сам день [today], иначе ближайший будущий. */
    private fun nextWeekday(today: LocalDate, dow: DayOfWeek): LocalDate {
        val diff = (dow.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7
        return dayPlus(today, diff.toLong())
    }

    private fun monthDayDate(w: String, today: LocalDate): LocalDate? {
        val words = w.split(" ")
        if (words.size !in 2..3) return null
        var day: Int? = null
        var month: Int? = null
        var year: Int? = null
        if (words[0].toIntOrNull() != null) {
            day = words[0].toInt()
            month = monthNum(words[1])
            if (words.size == 3) year = words[2].toIntOrNull()
        } else {
            month = monthNum(words[0])
            day = words[1].toIntOrNull()
            if (words.size == 3) year = words[2].toIntOrNull()
        }
        if (day == null || month == null || day !in 1..31) return null
        val y = year ?: today.year
        val date = safeDate(y, month, day) ?: return null
        if (year == null && date < today) {
            // «25 дек» в январе — прошедшая дата → следующий год
            return safeDate(y + 1, month, day)
        }
        return date
    }
}