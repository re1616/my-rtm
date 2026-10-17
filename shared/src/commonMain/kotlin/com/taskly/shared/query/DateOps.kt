package com.taskly.shared.query

import com.taskly.shared.recurrence.Recurrence
import kotlinx.datetime.LocalDate

/** Общие датовые хелперы query-пакета (избегают DateTimeUnit API). */
internal fun LocalDate.plusDays(n: Long): LocalDate = Recurrence.addDays(this, n)

/** Floored месяц-арифметика (kotlin.math.floorMod удалён из stdlib 2.4). */
internal fun floorModInt(a: Int, b: Int): Int {
    val r = a % b
    return if (r >= 0) r else r + b
}