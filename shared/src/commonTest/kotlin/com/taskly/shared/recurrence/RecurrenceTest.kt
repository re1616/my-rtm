package com.taskly.shared.recurrence

import com.taskly.shared.domain.model.RecurrenceFreq
import com.taskly.shared.domain.model.RepeatMode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Повторы (SPEC F-4, AC-5): RRULE parse/render, генерация серий, режимы every/after,
 * DST-кейс (Europe/Berlin, на JVM — JS без IANA-таймзоны), Москва (UTC+03).
 */
class RecurrenceTest {

    private val msk = TimeZone.of("UTC+03") // Europe/Moscow, без DST

    // ------------------------------------------------------------------ RRULE ----

    @Test
    fun rruleParseBasic() {
        val r = RRule.parse("FREQ=WEEKLY")
        assertNotNull(r)
        assertEquals(RecurrenceFreq.WEEKLY, r!!.freq)
        assertEquals(1, r.interval)
        assertEquals("FREQ=WEEKLY", r.toRruleString())
    }

    @Test
    fun rruleParseFullRoundtrip() {
        val str = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR;COUNT=10"
        val r = RRule.parse(str)
        assertNotNull(r)
        assertEquals(RecurrenceFreq.WEEKLY, r!!.freq)
        assertEquals(2, r.interval)
        assertEquals(listOf("MO", "WE", "FR"), r.byDay.map { it.toString() })
        assertEquals(10, r.count)
        assertEquals(str, r.toRruleString())
    }

    @Test
    fun rruleParseUntil() {
        val r = RRule.parse("FREQ=DAILY;UNTIL=20261231")
        assertNotNull(r)
        assertEquals(LocalDate(2026, 12, 31), r!!.until)
        assertEquals("FREQ=DAILY;UNTIL=20261231", r.toRruleString())
        val iso = RRule.parse("FREQ=DAILY;UNTIL=2026-12-31")
        assertEquals(LocalDate(2026, 12, 31), iso!!.until)
    }

    @Test
    fun rruleParseMonthlyOrdinal() {
        val r = RRule.parse("FREQ=MONTHLY;BYDAY=2TU,-1FR")
        assertNotNull(r)
        assertEquals(listOf("2TU", "-1FR"), r!!.byDay.map { it.toString() })
    }

    @Test
    fun rruleParseInvalid() {
        assertNull(RRule.parse(""))
        assertNull(RRule.parse("FREQ=HOURLY"))
        assertNull(RRule.parse("BYDAY=MO"))
        assertNull(RRule.parse("FREQ=WEEKLY;BYDAY=XX"))
        assertNull(RRule.parse("garbage"))
    }

    // ------------------------------------------------------------------ генерация ----

    @Test
    fun dailyOccurrences() {
        val r = RRule.parse("FREQ=DAILY;INTERVAL=1")!!
        assertEquals(
            listOf(LocalDate(2026, 10, 17), LocalDate(2026, 10, 18), LocalDate(2026, 10, 19)),
            Recurrence.occurrences(r, LocalDate(2026, 10, 17), 3),
        )
        val every3 = RRule.parse("FREQ=DAILY;INTERVAL=3")!!
        assertEquals(
            listOf(LocalDate(2026, 10, 17), LocalDate(2026, 10, 20), LocalDate(2026, 10, 23)),
            Recurrence.occurrences(every3, LocalDate(2026, 10, 17), 3),
        )
    }

    @Test
    fun weeklyOccurrences() {
        val r = RRule.parse("FREQ=WEEKLY")!!
        assertEquals(
            listOf(LocalDate(2026, 10, 17), LocalDate(2026, 10, 24), LocalDate(2026, 10, 31)),
            Recurrence.occurrences(r, LocalDate(2026, 10, 17), 3),
        )
    }

    @Test
    fun weeklyByDay() {
        // Пн/Ср/Пт каждую неделю, начиная с пятницы 17.10
        val r = RRule.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR")!!
        assertEquals(
            listOf(
                LocalDate(2026, 10, 19), // пн следующей недели (17.10 — пт)
                LocalDate(2026, 10, 21),
                LocalDate(2026, 10, 23),
                LocalDate(2026, 10, 26),
            ),
            Recurrence.occurrences(r, LocalDate(2026, 10, 17), 4),
        )
    }

    @Test
    fun weeklyByDayInterval2() {
        val r = RRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE")!!
        assertEquals(
            listOf(LocalDate(2026, 10, 26), LocalDate(2026, 10, 28), LocalDate(2026, 11, 9), LocalDate(2026, 11, 11)),
            Recurrence.occurrences(r, LocalDate(2026, 10, 17), 4),
        )
    }

    @Test
    fun monthlyPlainClamp() {
        val r = RRule.parse("FREQ=MONTHLY")!!
        assertEquals(LocalDate(2026, 11, 30), Recurrence.nextOccurrence(r, LocalDate(2026, 10, 31)))
        // серия с якорем 31-го числа: клампится в ноябре (30), возвращается в декабре (31)
        assertEquals(
            listOf(LocalDate(2026, 10, 31), LocalDate(2026, 11, 30), LocalDate(2026, 12, 31)),
            Recurrence.occurrences(r, LocalDate(2026, 10, 31), 3),
        )
    }

    @Test
    fun monthlyByMonthDay() {
        val r = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=1,15")!!
        assertEquals(
            listOf(LocalDate(2026, 10, 15), LocalDate(2026, 11, 1), LocalDate(2026, 11, 15)),
            Recurrence.occurrences(r, LocalDate(2026, 10, 10), 3),
        )
    }

    @Test
    fun monthlyOrdinalWeekday() {
        // Второй вторник месяца: 13.10.2026 → 10.11.2026
        val r = RRule.parse("FREQ=MONTHLY;BYDAY=2TU")!!
        assertEquals(LocalDate(2026, 11, 10), Recurrence.nextOccurrence(r, LocalDate(2026, 10, 13)))
        // Последняя пятница месяца: 30.10.2026 → 27.11.2026
        val r2 = RRule.parse("FREQ=MONTHLY;BYDAY=-1FR")!!
        assertEquals(LocalDate(2026, 11, 27), Recurrence.nextOccurrence(r2, LocalDate(2026, 10, 30)))
        assertEquals(listOf(LocalDate(2026, 10, 30), LocalDate(2026, 11, 27)), Recurrence.occurrences(r2, LocalDate(2026, 10, 18), 2))
    }

    @Test
    fun yearlyLeapDay() {
        val r = RRule.parse("FREQ=YEARLY")!!
        assertEquals(LocalDate(2025, 2, 28), Recurrence.nextOccurrence(r, LocalDate(2024, 2, 29)))
        // Серия от 29 февраля: клампится в невисокосные годы, возвращается к 29-му
        assertEquals(
            listOf(
                LocalDate(2024, 2, 29),
                LocalDate(2025, 2, 28),
                LocalDate(2026, 2, 28),
                LocalDate(2027, 2, 28),
                LocalDate(2028, 2, 29),
            ),
            Recurrence.occurrences(r, LocalDate(2024, 2, 29), 5),
        )
    }

    @Test
    fun yearlyByMonth() {
        val r = RRule.parse("FREQ=YEARLY;BYMONTH=12")!!
        assertEquals(LocalDate(2026, 12, 10), Recurrence.nextOccurrence(r, LocalDate(2026, 5, 10)))
        assertEquals(LocalDate(2027, 12, 10), Recurrence.nextOccurrence(r, LocalDate(2026, 12, 10)))
    }

    @Test
    fun untilStopsSeries() {
        val r = RRule.parse("FREQ=WEEKLY;UNTIL=20261031")!!
        assertEquals(
            listOf(LocalDate(2026, 10, 17), LocalDate(2026, 10, 24), LocalDate(2026, 10, 31)),
            Recurrence.occurrences(r, LocalDate(2026, 10, 17), 10),
        )
        assertNull(Recurrence.nextOccurrence(r, LocalDate(2026, 10, 31)))
    }

    // ------------------------------------------------------------------ every/after ----

    @Test
    fun everyModeFromPlannedDate() {
        val rule = RRule.parse("FREQ=WEEKLY")!!
        // «каждую неделю» от плановой даты: следующая = плановая + 7 дней
        assertEquals(
            LocalDate(2026, 10, 24),
            Recurrence.nextInstanceDate(rule, RepeatMode.EVERY, LocalDate(2026, 10, 17), null),
        )
    }

    @Test
    fun afterModeFromCompletionDate() {
        // «после 2 недель»: сдвиг от даты выполнения
        val rule = RRule.parse("FREQ=WEEKLY;INTERVAL=2")!!
        assertEquals(
            LocalDate(2026, 10, 31),
            Recurrence.nextInstanceDate(rule, RepeatMode.AFTER, LocalDate(2026, 10, 10), LocalDate(2026, 10, 17)),
        )
    }

    @Test
    fun afterModeDaily() {
        val rule = RRule.parse("FREQ=DAILY;INTERVAL=3")!!
        assertEquals(
            LocalDate(2026, 10, 20),
            Recurrence.nextInstanceDate(rule, RepeatMode.AFTER, null, LocalDate(2026, 10, 17)),
        )
    }

    @Test
    fun afterModeMonthly() {
        val rule = RRule.parse("FREQ=MONTHLY")!!
        assertEquals(
            LocalDate(2026, 11, 17),
            Recurrence.nextInstanceDate(rule, RepeatMode.AFTER, null, LocalDate(2026, 10, 17)),
        )
    }

    @Test
    fun everyNeedsAnchor() {
        val rule = RRule.parse("FREQ=DAILY")!!
        assertNull(Recurrence.nextInstanceDate(rule, RepeatMode.EVERY, null, null))
        // without anchor and completedAt there's nothing to shift from
        assertNull(Recurrence.nextInstanceDate(rule, RepeatMode.AFTER, null, null))
        // after without completedAt falls back to the anchor
        assertEquals(
            LocalDate(2026, 10, 18),
            Recurrence.nextInstanceDate(rule, RepeatMode.AFTER, LocalDate(2026, 10, 17), null),
        )
    }

    // ------------------------------------------------------------------ DST ----

    @Test
    fun dstSpringForwardBerlin() {
        val isJvm = com.taskly.shared.getPlatform().name.startsWith("JVM")
        if (!isJvm) return // JS не содержит IANA-базу (tzdb) — DST-кейс на JVM
        val berlin = TimeZone.of("Europe/Berlin")
        val daily = RRule.parse("FREQ=DAILY")!!
        val anchor = LocalDate(2026, 3, 28)
        val next = Recurrence.nextOccurrence(daily, anchor)
        assertEquals(LocalDate(2026, 3, 29), next)
        // Переход на летнее время: 2026-03-29 02:00 → 03:00 (CET→CEST)
        val t1 = Recurrence.zonedInstant(anchor, LocalTime(12, 0, 0, 0), berlin)
        val t2 = Recurrence.zonedInstant(LocalDate(2026, 3, 29), LocalTime(12, 0, 0, 0), berlin)
        // 24h в стенных часах, но UTC-дельта 23h (потерянный час DST)
        assertEquals(23 * 3_600_000L, t2.toEpochMilliseconds() - t1.toEpochMilliseconds())
    }

    @Test
    fun moscowNoDst() {
        val daily = RRule.parse("FREQ=DAILY")!!
        val anchor = LocalDate(2026, 3, 28)
        val next = Recurrence.nextOccurrence(daily, anchor)!!
        assertEquals(LocalDate(2026, 3, 29), next)
        val t1 = Recurrence.zonedInstant(anchor, LocalTime(12, 0, 0, 0), msk)
        val t2 = Recurrence.zonedInstant(next, LocalTime(12, 0, 0, 0), msk)
        assertEquals(24 * 3_600_000L, t2.toEpochMilliseconds() - t1.toEpochMilliseconds())
    }

    // ------------------------------------------------------------------ helpers ----

    private fun LocalDate.plusDays(n: Long): LocalDate = LocalDate.fromEpochDays(this.toEpochDays() + n)
}