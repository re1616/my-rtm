package com.taskly.shared.smartadd

import kotlin.math.roundToInt

/**
 * Парсер оценки времени (SPEC F-2): «=30 мин», «=1ч», «=1.5ч», «=2h», «=45m»,
 * «=1 hour», «= 30 minutes». Возвращает минуты.
 */
object EstimateParser {

    /** null — не оценка. Минуты (дробные часы округляются). */
    fun parse(phrase: String): Int? {
        val w = phrase.trim().replace(',', '.').replace(Regex("""\s+"""), " ")
        if (w.isEmpty()) return null
        val m = Regex("""^(\d+(?:\.\d+)?)\s*(мин|минута|минуты|минут|мин\.|ч|час|часа|часов|ч\.|m|min|mins|minute|minutes|h|hr|hrs|hour|hours)?$""").matchEntire(w)
            ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        val minutes = when {
            unit.isEmpty() -> value
            unit == "мин" || unit == "минута" || unit == "минуты" || unit == "минут" || unit == "мин."
                || unit == "m" || unit == "min" || unit == "mins" || unit == "minute" || unit == "minutes" -> value
            unit == "ч" || unit == "час" || unit == "часа" || unit == "часов" || unit == "ч."
                || unit == "h" || unit == "hr" || unit == "hrs" || unit == "hour" || unit == "hours" -> value * 60
            else -> value
        }
        val rounded = minutes.roundToInt()
        if (rounded <= 0 || rounded > 10080) return null // разумный предел — неделя
        return rounded
    }
}