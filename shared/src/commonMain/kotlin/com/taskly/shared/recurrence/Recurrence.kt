package com.taskly.shared.recurrence

import com.taskly.shared.domain.model.RecurrenceFreq
import com.taskly.shared.domain.model.RepeatMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant

/**
 * Расчёт повторов (SPEC F-4): nextOccurrence для RRULE плюс режимы every/after.
 * Работает на датах; для времени с учётом часового пояса — [zonedInstant]
 * (DST-кейс: стена-часы сохраняются, UTC-смещение меняется).
 *
 * COUNT-семантика (RFC 5545): серия завершается после N экземпляров.
 * Функции [nextOccurrence] и [nextInstanceDate] имеют перегрузки с
 * [occurrenceIndex] для явного указания порядкового номера последнего экземпляра.
 *
 * DST-политика (SYNC-4):
 * - Весенний переход (gap): несуществующее локальное время сдвигается вперёд
 *   на 1 час (следующее существующее время) — см. [zonedInstantResolve].
 * - Осенний переход (overlap): неоднозначное время — берётся первое вхождение
 *   (то, что до перевода стрелок).
 */
object Recurrence {

    // ---------- Дата-арифметика без DateTimeUnit (кросс-платформенно) ----------

    fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> throw IllegalArgumentException("Invalid month: $month")
    }

    fun addDays(d: LocalDate, n: Long): LocalDate = LocalDate.fromEpochDays(d.toEpochDays() + n)

    fun addMonthsClamped(d: LocalDate, months: Int): LocalDate {
        val total = d.year * 12 + (d.monthNumber - 1) + months
        var y = total / 12
        var m = total % 12
        if (m < 0) { m += 12; y -= 1 }
        val month = m + 1
        val last = daysInMonth(y, month)
        return LocalDate(y, month, minOf(d.dayOfMonth, last))
    }

    fun addYearsClamped(d: LocalDate, years: Int): LocalDate =
        addMonthsClamped(d, years * 12)

    // ---------- Основной движок ----------

    /**
     * Следующий экземпляр серии строго после [after].
     * Учитывает UNTIL; COUNT НЕ учитывается — используйте перегрузку
     * с [occurrenceIndex] для проверки COUNT.
     */
    fun nextOccurrence(rule: RRule, after: LocalDate): LocalDate? =
        nextOccurrenceInternal(rule, after, null, null)

    /**
     * Следующий экземпляр серии строго после [after] с учётом COUNT.
     * [occurrenceIndex] — 0-based индекс даты [after] в серии.
     * Если `occurrenceIndex >= count - 1`, возвращается null (серия завершена).
     */
    fun nextOccurrence(rule: RRule, after: LocalDate, occurrenceIndex: Int): LocalDate? =
        nextOccurrenceInternal(rule, after, null, occurrenceIndex)

    /** Внутренняя: с сохранением [seriesDay] для plain-monthly/yearly (день-якорь серии). */
    private fun nextOccurrenceInternal(
        rule: RRule,
        after: LocalDate,
        seriesDay: Int?,
        occurrenceIndex: Int?,
    ): LocalDate? {
        val next = when (rule.freq) {
            RecurrenceFreq.DAILY -> nextDaily(rule, after)
            RecurrenceFreq.WEEKLY -> nextWeekly(rule, after)
            RecurrenceFreq.MONTHLY -> nextMonthly(rule, after, seriesDay)
            RecurrenceFreq.YEARLY -> nextYearly(rule, after, seriesDay)
        }
        if (next == null) return null
        // COUNT-check: если [after] — последний разрешённый экземпляр, дальше ничего нет
        if (occurrenceIndex != null) {
            rule.count?.let { if (occurrenceIndex >= it - 1) return null }
        }
        rule.until?.let { if (next > it) return null }
        return next
    }

    /**
     * Первые [limit] экземпляров серии >= [from].
     * Если в RRULE указан COUNT, берётся min(limit, count).
     */
    fun occurrences(rule: RRule, from: LocalDate, limit: Int): List<LocalDate> {
        val effectiveLimit = minOf(rule.count ?: Int.MAX_VALUE, limit)
        return occurrencesInternal(rule, from, effectiveLimit)
    }

    /**
     * Все экземпляры серии >= [from] в пределах COUNT из RRULE.
     * Если COUNT не указан, возвращается пустой список (неограниченная серия —
     * используйте [occurrences] с явным limit).
     */
    fun occurrences(rule: RRule, from: LocalDate): List<LocalDate> {
        val count = rule.count ?: return emptyList()
        return occurrencesInternal(rule, from, count)
    }

    private fun occurrencesInternal(rule: RRule, from: LocalDate, effectiveLimit: Int): List<LocalDate> {
        val result = mutableListOf<LocalDate>()
        // occurrenceIndex для from-1 равен -1: before first -> разрешаем переход к первой
        val first = if (matchesInstance(rule, from)) {
            from
        } else {
            nextOccurrenceInternal(
                rule, LocalDate.fromEpochDays(from.toEpochDays() - 1), null, -1,
            )
        }
        val seriesDay = from.dayOfMonth
        var cur = first
        var idx = 0
        while (cur != null && result.size < effectiveLimit) {
            result.add(cur)
            cur = nextOccurrenceInternal(rule, cur, seriesDay, idx)
            idx++
        }
        return result
    }

    /**
     * Дата следующего экземпляра по режиму:
     * - EVERY — от плановой даты [anchor] (последний экземпляр);
     * - AFTER — сдвиг от даты выполнения [completedAt] на interval*FREQ (RTM-семантика «after N»).
     *
     * COUNT не проверяется — используйте перегрузку с [occurrenceIndex].
     */
    fun nextInstanceDate(rule: RRule, mode: RepeatMode, anchor: LocalDate?, completedAt: LocalDate?): LocalDate? {
        return when (mode) {
            RepeatMode.EVERY -> anchor?.let { nextOccurrence(rule, it) }
            RepeatMode.AFTER -> {
                val base = completedAt ?: anchor ?: return null
                shiftByFreq(base, rule)
            }
        }
    }

    /**
     * Дата следующего экземпляра с учётом COUNT.
     * [occurrenceIndex] — 0-based индекс [anchor] (EVERY) или [completedAt] (AFTER) в серии.
     */
    fun nextInstanceDate(
        rule: RRule,
        mode: RepeatMode,
        anchor: LocalDate?,
        completedAt: LocalDate?,
        occurrenceIndex: Int,
    ): LocalDate? {
        return when (mode) {
            RepeatMode.EVERY -> anchor?.let { nextOccurrence(rule, it, occurrenceIndex) }
            RepeatMode.AFTER -> {
                val base = completedAt ?: anchor ?: return null
                if (rule.count != null && occurrenceIndex >= rule.count - 1) return null
                shiftByFreq(base, rule)
            }
        }
    }

    /** AFTER-семантика: базовая дата + interval периодов FREQ. */
    private fun shiftByFreq(base: LocalDate, rule: RRule): LocalDate = when (rule.freq) {
        RecurrenceFreq.DAILY -> addDays(base, rule.interval.toLong())
        RecurrenceFreq.WEEKLY -> addDays(base, (rule.interval * 7).toLong())
        RecurrenceFreq.MONTHLY -> addMonthsClamped(base, rule.interval)
        RecurrenceFreq.YEARLY -> addYearsClamped(base, rule.interval)
    }

    /** Инстант «дата + местное время» в поясе [tz] — для DST-проверок и сервера. */
    fun zonedInstant(date: LocalDate, time: LocalTime, tz: TimeZone): Instant =
        LocalDateTime(date, time).toInstant(tz)

    /**
     * Инстант «дата + местное время» с разрешением DST-перехода.
     * - **Spring-forward (gap):** если время не существует (например, 02:30 при
     *   переводе 02:00→03:00), сдвигается на +1 час до существующего времени.
     * - **Fall-back (overlap):** kotlinx.datetime по умолчанию выбирает первое
     *   вхождение (до перевода), что соответствует политике SYNC-4.
     */
    fun zonedInstantResolve(date: LocalDate, time: LocalTime, tz: TimeZone): Instant {
        val ldt = LocalDateTime(date, time)
        return try {
            ldt.toInstant(tz)
        } catch (e: IllegalArgumentException) {
            // Spring-forward gap: продвигаем на 1 час (стандартный размер gap)
            val nextHour = if (time.hour < 23) time.hour + 1 else 0
            val nextDate = if (time.hour < 23) date else addDays(date, 1)
            LocalDateTime(nextDate, LocalTime(nextHour, time.minute, time.second)).toInstant(tz)
        }
    }

    // ---------- Почастотные генераторы ----------

    private fun matchesDayFilters(d: LocalDate, rule: RRule): Boolean {
        if (rule.byMonth.isNotEmpty() && d.monthNumber !in rule.byMonth) return false
        if (rule.byMonthDay.isNotEmpty() && d.dayOfMonth !in rule.byMonthDay) return false
        return true
    }

    /** Является ли [d] экземпляром серии [rule] (для инклюзивного occurrences). */
    private fun matchesInstance(rule: RRule, d: LocalDate): Boolean {
        if (rule.byMonth.isNotEmpty() && d.monthNumber !in rule.byMonth) return false
        return when (rule.freq) {
            RecurrenceFreq.DAILY -> rule.byMonthDay.isEmpty() || d.dayOfMonth in rule.byMonthDay
            RecurrenceFreq.WEEKLY -> rule.byDay.isEmpty() || rule.byDay.any { it.day == d.dayOfWeek }
            RecurrenceFreq.MONTHLY -> {
                if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) true
                else d in candidatesInMonth(d, rule)
            }
            RecurrenceFreq.YEARLY -> {
                if (rule.byDay.isNotEmpty()) d in candidatesInMonth(d, rule)
                else if (rule.byMonthDay.isNotEmpty()) d.dayOfMonth in rule.byMonthDay
                else true
            }
        }
    }

    private fun nextDaily(rule: RRule, after: LocalDate): LocalDate? {
        var d = addDays(after, rule.interval.toLong())
        repeat(400) {
            if (matchesDayFilters(d, rule)) return d
            d = addDays(d, 1)
        }
        return null
    }

    private fun nextWeekly(rule: RRule, after: LocalDate): LocalDate? {
        if (rule.byDay.isEmpty()) {
            return addDays(after, (rule.interval * 7).toLong())
        }
        val days = rule.byDay.map { it.day }.toSet()
        val anchorMonday = addDays(after, -(after.dayOfWeek.isoDayNumber - 1).toLong())
        var d = addDays(after, 1)
        repeat(7 * rule.interval + 7) {
            val weeks = (d.toEpochDays() - anchorMonday.toEpochDays()) / 7
            if (weeks % rule.interval == 0L && d.dayOfWeek in days && matchesDayFilters(d, rule)) return d
            d = addDays(d, 1)
        }
        return null
    }

    /**
     * MONTHLY. Если правило перечисляет несколько дней внутри месяца
     * (BYMONTHDAY или BYDAY) — следующий экземпляр может быть в том же месяце;
     * иначе серия шагает на interval месяцев, сохраняя день-якорь [seriesDay].
     */
    private fun nextMonthly(rule: RRule, after: LocalDate, seriesDay: Int?): LocalDate? {
        val plain = rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()
        val sameMonthAllowed = !plain
        val step = if (sameMonthAllowed) 0 else rule.interval
        repeat(96) { k ->
            val offset = step + k * rule.interval
            val total = after.year * 12 + (after.monthNumber - 1) + offset
            var y = total / 12
            var m = total % 12
            if (m < 0) { m += 12; y -= 1 }
            m += 1
            if (rule.byMonth.isNotEmpty() && m !in rule.byMonth) return@repeat
            val cands = if (plain) {
                val day = minOf(seriesDay ?: after.dayOfMonth, daysInMonth(y, m))
                listOf(LocalDate(y, m, day))
            } else {
                candidatesInMonth(LocalDate(y, m, 1), rule)
            }
            cands.firstOrNull { it > after }?.let { return it }
        }
        return null
    }

    /** Все даты месяца по BYDAY/BYMONTHDAY. */
    private fun candidatesInMonth(target: LocalDate, rule: RRule): List<LocalDate> {
        val y = target.year
        val m = target.monthNumber
        val out = mutableListOf<LocalDate>()
        if (rule.byDay.isNotEmpty()) {
            for (entry in rule.byDay) {
                val ordinal = entry.ordinal
                if (ordinal == null) {
                    out += allWeekdaysInMonth(y, m, entry.day)
                } else {
                    nthWeekdayInMonth(y, m, ordinal, entry.day)?.let { out += it }
                }
            }
        } else if (rule.byMonthDay.isNotEmpty()) {
            for (d in rule.byMonthDay) {
                if (d in 1..daysInMonth(y, m)) out += LocalDate(y, m, d)
            }
        }
        return out.distinct().sorted()
    }

    /** ordinal: 1..5 (первый..пятый), -1..-5 (последний..). null если такого дня в месяце нет. */
    fun nthWeekdayInMonth(year: Int, month: Int, ordinal: Int, dow: DayOfWeek): LocalDate? {
        val last = daysInMonth(year, month)
        return if (ordinal > 0) {
            val first = firstWeekdayDay(year, month, dow)
            val day = first + (ordinal - 1) * 7
            if (day <= last) LocalDate(year, month, day) else null
        } else {
            val lastDay = lastWeekdayDay(year, month, dow)
            val day = lastDay + (ordinal + 1) * 7  // ordinal=-1 → lastDay; -2 → lastDay-7
            if (day >= 1) LocalDate(year, month, day) else null
        }
    }

    fun allWeekdaysInMonth(year: Int, month: Int, dow: DayOfWeek): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        val last = daysInMonth(year, month)
        for (d in 1..last) {
            val date = LocalDate(year, month, d)
            if (date.dayOfWeek == dow) out += date
        }
        return out
    }

    private fun firstWeekdayDay(year: Int, month: Int, dow: DayOfWeek): Int {
        var d = 1
        while (LocalDate(year, month, d).dayOfWeek != dow) d++
        return d
    }

    private fun lastWeekdayDay(year: Int, month: Int, dow: DayOfWeek): Int {
        var d = daysInMonth(year, month)
        while (LocalDate(year, month, d).dayOfWeek != dow) d--
        return d
    }

    private fun nextYearly(rule: RRule, after: LocalDate, seriesDay: Int?): LocalDate? {
        if (rule.byDay.isNotEmpty()) return null // годичные BYDAY — вне поддержки (документировано)
        val months = (if (rule.byMonth.isNotEmpty()) rule.byMonth else listOf(after.monthNumber)).sorted()
        var y = after.year
        repeat(80) {
            for (m in months) {
                if (rule.byMonthDay.isNotEmpty()) {
                    val cands = rule.byMonthDay.sorted().mapNotNull { d ->
                        if (d in 1..daysInMonth(y, m)) LocalDate(y, m, d) else null
                    }.filter { it > after }
                    if (cands.isNotEmpty()) return cands.min()
                } else {
                    val day = minOf(seriesDay ?: after.dayOfMonth, daysInMonth(y, m))
                    val cand = LocalDate(y, m, day)
                    if (cand > after) return cand
                }
            }
            y += rule.interval
        }
        return null
    }
}