package com.taskly.android.feature.tasks

import com.taskly.shared.smartadd.DateParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Due dates typed as text in the task card ("завтра 17:00", "пт", "25 дек") — the shared parser. */
object DueText {
    /** (ISO instant or null, has time) — `""` clears the date; null when the text is not a date. */
    fun parse(text: String, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): Pair<String?, Boolean>? {
        if (text.isBlank()) return null to false
        val kToday = kotlinx.datetime.LocalDate.parse(today.toString())
        val tz = kotlinx.datetime.TimeZone.of(zone.id)
        val parsed = DateParser.parseDatePhrase(text, kToday, tz) ?: return null
        val date = LocalDate.parse(parsed.date.toString())
        val time = if (parsed.hasTime && parsed.time != null) LocalTime.parse(parsed.time.toString()) else LocalTime.MIDNIGHT
        return date.atTime(time).atZone(zone).toInstant().toString() to parsed.hasTime
    }

    /** "сегодня", "завтра 17:00", "25 дек." in the user's locale. */
    fun format(dueAt: String?, hasTime: Boolean, zone: ZoneId, labels: Labels, today: LocalDate = LocalDate.now(zone), locale: Locale = Locale.getDefault()): String? {
        val at = dueAt?.let { runCatching { Instant.parse(it).atZone(zone) }.getOrNull() } ?: return null
        val day = when (at.toLocalDate()) {
            today -> labels.today
            today.plusDays(1) -> labels.tomorrow
            today.minusDays(1) -> labels.yesterday
            else -> at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
        return if (hasTime) "$day ${at.format(DateTimeFormatter.ofPattern("HH:mm", locale))}" else day
    }

    fun isOverdue(dueAt: String?, hasTime: Boolean, zone: ZoneId, now: Instant = Instant.now()): Boolean {
        val at = dueAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        return if (hasTime) at.isBefore(now) else at.atZone(zone).toLocalDate().isBefore(now.atZone(zone).toLocalDate())
    }

    data class Labels(val today: String, val tomorrow: String, val yesterday: String)
}
