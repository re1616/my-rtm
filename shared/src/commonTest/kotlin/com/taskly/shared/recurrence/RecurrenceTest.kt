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

    // ------------------------------------------------------------------ COUNT ----

    @Test
    fun countThreeWeekly() {
        // COUNT=3, еженедельно: ровно 3 даты
        val r = RRule.parse("FREQ=WEEKLY;COUNT=3")!!
        val dates = Recurrence.occurrences(r, LocalDate(2026, 10, 17))
        assertEquals(3, dates.size)
        assertEquals(
            listOf(LocalDate(2026, 10, 17), LocalDate(2026, 10, 24), LocalDate(2026, 10, 31)),
            dates,
        )
    }

    @Test
    fun countOne() {
        // COUNT=1: только начальная дата
        val r = RRule.parse("FREQ=DAILY;COUNT=1")!!
        assertEquals(
            listOf(LocalDate(2026, 10, 17)),
            Recurrence.occurrences(r, LocalDate(2026, 10, 17)),
        )
    }

    @Test
    fun countWithInterval() {
        // Каждые 2 недели, COUNT=2
        val r = RRule.parse("FREQ=WEEKLY;INTERVAL=2;COUNT=2")!!
        assertEquals(
            listOf(LocalDate(2026, 10, 17), LocalDate(2026, 10, 31)),
            Recurrence.occurrences(r, LocalDate(2026, 10, 17)),
        )
    }

    @Test
    fun countWithByDay() {
        // Пн/Ср/Пт, COUNT=4: 4 даты
        val r = RRule.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=4")!!
        assertEquals(
            listOf(
                LocalDate(2026, 10, 19),
                LocalDate(2026, 10, 21),
                LocalDate(2026, 10, 23),
                LocalDate(2026, 10, 26),
            ),
            Recurrence.occurrences(r, LocalDate(2026, 10, 17)),
        )
    }

    @Test
    fun countWithUntil() {
        // COUNT=52, но UNTIL раньше: останавливается на UNTIL
        val r = RRule.parse("FREQ=WEEKLY;COUNT=52;UNTIL=20261115")!!
        val from = LocalDate(2026, 10, 17)
        val dates = Recurrence.occurrences(r, from, 100)
        // 17.10 → 24.10 → 31.10 → 7.11 → 14.11 — до UNTIL=15.11
        assertEquals(5, dates.size)
        assertEquals(LocalDate(2026, 11, 14), dates.last())
    }

    @Test
    fun countExplicitLimit() {
        // occurrences с явным limit меньше COUNT: побеждает limit
        val r = RRule.parse("FREQ=DAILY;COUNT=10")!!
        assertEquals(3, Recurrence.occurrences(r, LocalDate(2026, 10, 17), 3).size)
    }

    @Test
    fun countNextOccurrenceWithIndex() {
        // Пошаговый обход с occurrenceIndex
        val r = RRule.parse("FREQ=WEEKLY;COUNT=3")!!
        val first = LocalDate(2026, 10, 17)
        val second = Recurrence.nextOccurrence(r, first, 0) // after=index0 → получаем index1
        assertEquals(LocalDate(2026, 10, 24), second)
        val third = Recurrence.nextOccurrence(r, second!!, 1) // after=index1 → получаем index2
        assertEquals(LocalDate(2026, 10, 31), third)
        assertNull(Recurrence.nextOccurrence(r, third!!, 2)) // after=index2 → null (COUNT exhausted)
    }

    @Test
    fun countNextInstanceDateEvery() {
        // EVERY с COUNT: 3-й вызов возвращает null
        val r = RRule.parse("FREQ=WEEKLY;COUNT=3")!!
        val first = LocalDate(2026, 10, 17)
        val second = Recurrence.nextInstanceDate(r, RepeatMode.EVERY, first, null, 0)
        assertEquals(LocalDate(2026, 10, 24), second)
        val third = Recurrence.nextInstanceDate(r, RepeatMode.EVERY, second, null, 1)
        assertEquals(LocalDate(2026, 10, 31), third)
        assertNull(Recurrence.nextInstanceDate(r, RepeatMode.EVERY, third, null, 2))
    }

    @Test
    fun countNoCountReturnsEmpty() {
        // occurrences(rule, from) без COUNT → emptyList
        val r = RRule.parse("FREQ=WEEKLY")!! // нет COUNT
        assertEquals(emptyList<LocalDate>(), Recurrence.occurrences(r, LocalDate(2026, 10, 17)))
    }

    @Test
    fun rruleParseCount() {
        val r = RRule.parse("FREQ=WEEKLY;COUNT=5")
        assertNotNull(r)
        assertEquals(5, r!!.count)
        assertEquals("FREQ=WEEKLY;COUNT=5", r.toRruleString())
    }

    @Test
    fun rruleParseCountInvalid() {
        // Неположительный COUNT → count=null (RRule валиден, парсер отбрасывает невалидный COUNT)
        val r = RRule.parse("FREQ=WEEKLY;COUNT=0")
        assertNotNull(r)
        assertNull(r!!.count)
        assertEquals("FREQ=WEEKLY", r.toRruleString())
        val r2 = RRule.parse("FREQ=WEEKLY;COUNT=-1")
        assertNotNull(r2)
        assertNull(r2!!.count)
    }

    // ------------------------------------------------------------------ DST ----

    @Test
    fun dstSpringForwardResolve() {
        // JVM-only: JS не имеет IANA tzdb
        val isJvm = com.taskly.shared.getPlatform().name.startsWith("JVM")
        if (!isJvm) return
        val berlin = TimeZone.of("Europe/Berlin")
        // 2025-03-30 02:30 — не существует (02:00→03:00)
        val resolved = Recurrence.zonedInstantResolve(LocalDate(2025, 3, 30), LocalTime(2, 30), berlin)
        // Должен сдвинуться до 03:30 (первый существующий час после gap)
        val expected = Recurrence.zonedInstant(LocalDate(2025, 3, 30), LocalTime(3, 30), berlin)
        assertEquals(expected, resolved)
    }

    @Test
    fun dstFallBackOverlap() {
        val isJvm = com.taskly.shared.getPlatform().name.startsWith("JVM")
        if (!isJvm) return
        val berlin = TimeZone.of("Europe/Berlin")
        // 2025-10-26 02:30 — неоднозначное время (CEST 02:30 до перевода,
        // затем CET 02:30 после перевода 03:00→02:00)
        // kotlinx.datetime берёт первое вхождение (CEST — до перевода)
        val overlapping = Recurrence.zonedInstant(LocalDate(2025, 10, 26), LocalTime(2, 30), berlin)
        // Проверяем, что это CEST (UTC+02), а не CET (UTC+01):
        // 25.10 02:30 CEST = UTC 00:30; 26.10 02:30 CEST (первое) = UTC 00:30
        // разница ровно 24h
        val cest = Recurrence.zonedInstant(LocalDate(2025, 10, 25), LocalTime(2, 30), berlin)
        assertEquals(24 * 3_600_000L, overlapping.toEpochMilliseconds() - cest.toEpochMilliseconds())
    }

    @Test
    fun dstSpringForwardDailySequence() {
        val isJvm = com.taskly.shared.getPlatform().name.startsWith("JVM")
        if (!isJvm) return
        val berlin = TimeZone.of("Europe/Berlin")
        val daily = RRule.parse("FREQ=DAILY")!!
        // 28.03 → 29.03 → 30.03 (переход) → 31.03
        val date1 = LocalDate(2025, 3, 28)
        val date2 = Recurrence.nextOccurrence(daily, date1)!!
        val date3 = Recurrence.nextOccurrence(daily, date2)!!
        val date4 = Recurrence.nextOccurrence(daily, date3)!!
        assertEquals(LocalDate(2025, 3, 29), date2)
        assertEquals(LocalDate(2025, 3, 30), date3) // spring-forward — дата существует
        assertEquals(LocalDate(2025, 3, 31), date4)
        // UTC-разница: 28.03→29.03 = 24h, 29.03→30.03 = 23h (потерянный час)
        val t1 = Recurrence.zonedInstant(date1, LocalTime(12, 0), berlin)
        val t2 = Recurrence.zonedInstant(date2, LocalTime(12, 0), berlin)
        val t3 = Recurrence.zonedInstant(date3, LocalTime(12, 0), berlin)
        assertEquals(24 * 3_600_000L, t2.toEpochMilliseconds() - t1.toEpochMilliseconds())
        assertEquals(23 * 3_600_000L, t3.toEpochMilliseconds() - t2.toEpochMilliseconds())
    }

    @Test
    fun dstFallBackDailySequence() {
        val isJvm = com.taskly.shared.getPlatform().name.startsWith("JVM")
        if (!isJvm) return
        val berlin = TimeZone.of("Europe/Berlin")
        val daily = RRule.parse("FREQ=DAILY")!!
        // 25.10 → 26.10 (переход) → 27.10
        val date1 = LocalDate(2025, 10, 25)
        val date2 = Recurrence.nextOccurrence(daily, date1)!!
        val date3 = Recurrence.nextOccurrence(daily, date2)!!
        assertEquals(LocalDate(2025, 10, 26), date2)
        assertEquals(LocalDate(2025, 10, 27), date3)
        // UTC-разница: 25.10→26.10 = 25h (лишний час при падении)
        val t1 = Recurrence.zonedInstant(date1, LocalTime(12, 0), berlin)
        val t2 = Recurrence.zonedInstant(date2, LocalTime(12, 0), berlin)
        val t3 = Recurrence.zonedInstant(date3, LocalTime(12, 0), berlin)
        assertEquals(25 * 3_600_000L, t2.toEpochMilliseconds() - t1.toEpochMilliseconds())
        assertEquals(24 * 3_600_000L, t3.toEpochMilliseconds() - t2.toEpochMilliseconds())
    }

    private fun LocalDate.plusDays(n: Long): LocalDate = LocalDate.fromEpochDays(this.toEpochDays() + n)
}