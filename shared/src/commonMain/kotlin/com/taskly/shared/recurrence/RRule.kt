package com.taskly.shared.recurrence

import com.taskly.shared.domain.model.RecurrenceFreq
import kotlinx.datetime.DayOfWeek

/**
 * Один элемент BYDAY: "MO" (каждый понедельник), "2TU" (второй вторник),
 * "-1FR" (последняя пятница). ordinal == null означает «каждый такой день недели».
 */
data class WeeklyDay(
    val ordinal: Int?,
    val day: DayOfWeek,
) {
    override fun toString(): String =
        (ordinal?.toString() ?: "") + DAY_ABBR[day]

    companion object {
        val DAY_ABBR: Map<DayOfWeek, String> = mapOf(
            DayOfWeek.MONDAY to "MO",
            DayOfWeek.TUESDAY to "TU",
            DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR",
            DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )

        /** Разбор "MO" / "2TU" / "-1FR". null — невалидно. */
        fun parse(raw: String): WeeklyDay? {
            val s = raw.trim().uppercase()
            if (s.isEmpty()) return null
            val abbrToDay = DAY_ABBR.entries.associate { (k, v) -> v to k }
            val suffix = s.takeLast(2)
            val day = abbrToDay[suffix] ?: return null
            val prefix = s.dropLast(2)
            if (prefix.isEmpty()) return WeeklyDay(null, day)
            val ordinal = prefix.toIntOrNull() ?: return null
            if (ordinal == 0 || ordinal < -5 || ordinal > 5) return null
            return WeeklyDay(ordinal, day)
        }
    }
}

/**
 * Разобранный RRULE (RFC 5545), подмножество, нужное Taskly (SPEC F-4):
 * FREQ, INTERVAL, BYDAY, BYMONTHDAY, BYMONTH, COUNT, UNTIL.
 */
data class RRule(
    val freq: RecurrenceFreq,
    val interval: Int = 1,
    val byDay: List<WeeklyDay> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val count: Int? = null,
    val until: kotlinx.datetime.LocalDate? = null,
) {
    /** Каноническая RRULE-строка для хранения в БД и передачи в sync-протоколе. */
    fun toRruleString(): String {
        val parts = mutableListOf("FREQ=${freq.name}")
        if (interval > 1) parts.add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) parts.add("BYDAY=" + byDay.joinToString(","))
        if (byMonthDay.isNotEmpty()) parts.add("BYMONTHDAY=" + byMonthDay.joinToString(","))
        if (byMonth.isNotEmpty()) parts.add("BYMONTH=" + byMonth.joinToString(","))
        count?.let { parts.add("COUNT=$it") }
        until?.let {
            parts.add(
                "UNTIL=" +
                    it.year.toString().padStart(4, '0') +
                    it.monthNumber.toString().padStart(2, '0') +
                    it.dayOfMonth.toString().padStart(2, '0')
            )
        }
        return parts.joinToString(";")
    }

    companion object {
        val ALL_DAYS: List<DayOfWeek> = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
        )

        /** Ленивый разбор RRULE-строки; null при невалидном FREQ/структуре. */
        fun parse(raw: String): RRule? {
            val s = raw.trim().uppercase()
            if (s.isEmpty()) return null
            val fields = mutableMapOf<String, String>()
            for (part in s.split(";")) {
                if (part.isEmpty()) continue
                val idx = part.indexOf('=')
                if (idx <= 0) return null
                fields[part.substring(0, idx)] = part.substring(idx + 1)
            }
            val freq = RecurrenceFreq.parse(fields["FREQ"] ?: return null) ?: return null
            val intervalRaw = fields["INTERVAL"]
            val interval = intervalRaw?.toIntOrNull()?.takeIf { it > 0 } ?: 1

            val byDay = mutableListOf<WeeklyDay>()
            fields["BYDAY"]?.split(",")?.forEach {
                val wd = WeeklyDay.parse(it) ?: return null
                byDay.add(wd)
            }
            val byMonthDay = fields["BYMONTHDAY"]?.split(",")?.map { it.toIntOrNull() ?: return null }
                ?: emptyList()
            val byMonth = fields["BYMONTH"]?.split(",")?.map { it.toIntOrNull() ?: return null }
                ?: emptyList()
            val count = fields["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
            val until = fields["UNTIL"]?.let { parseUntil(it) }

            if (byDay.isNotEmpty() && freq == RecurrenceFreq.DAILY) {
                // BYDAY для DAILY не поддерживаем — трактуем как каждый день
                return RRule(freq, interval, emptyList(), byMonthDay, byMonth, count, until)
            }
            return RRule(freq, interval, byDay, byMonthDay, byMonth, count, until)
        }

        /** UNTIL принимаем в формате yyyyMMdd (RFC) или yyyy-MM-dd. */
        private fun parseUntil(raw: String): kotlinx.datetime.LocalDate? {
            val compact = Regex("""^(\d{4})(\d{2})(\d{2})$""").matchEntire(raw)
            if (compact != null) {
                val (y, m, d) = compact.destructured
                return try {
                    kotlinx.datetime.LocalDate(y.toInt(), m.toInt(), d.toInt())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            val iso = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(raw)
            if (iso != null) {
                val (y, m, d) = iso.destructured
                return try {
                    kotlinx.datetime.LocalDate(y.toInt(), m.toInt(), d.toInt())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            return null
        }
    }
}