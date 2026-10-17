package com.taskly.shared.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Приоритет задачи: 1/2/3 или отсутствует (SPEC F-1). */
@Serializable
enum class Priority {
    @SerialName("none")
    NONE,

    @SerialName("1")
    HIGH,

    @SerialName("2")
    MEDIUM,

    @SerialName("3")
    LOW;

    val level: Int?
        get() = when (this) {
            NONE -> null
            HIGH -> 1
            MEDIUM -> 2
            LOW -> 3
        }

    companion object {
        fun fromLevel(level: Int?): Priority = when (level) {
            1 -> HIGH
            2 -> MEDIUM
            3 -> LOW
            else -> NONE
        }

        fun parse(raw: String): Priority? = when (raw.trim().lowercase()) {
            "1", "high", "высокий" -> HIGH
            "2", "medium", "средний" -> MEDIUM
            "3", "low", "низкий" -> LOW
            "none", "0" -> NONE
            else -> null
        }
    }
}

/** Режим повтора: every — от плановой даты, after — от даты выполнения (SPEC F-4). */
@Serializable
enum class RepeatMode {
    @SerialName("every")
    EVERY,

    @SerialName("after")
    AFTER
}

/** Частота повтора (RRULE FREQ). */
@Serializable
enum class RecurrenceFreq {
    @SerialName("DAILY")
    DAILY,

    @SerialName("WEEKLY")
    WEEKLY,

    @SerialName("MONTHLY")
    MONTHLY,

    @SerialName("YEARLY")
    YEARLY;

    companion object {
        fun parse(raw: String): RecurrenceFreq? = when (raw.uppercase()) {
            "DAILY", "DAY" -> DAILY
            "WEEKLY", "WEEK" -> WEEKLY
            "MONTHLY", "MONTH" -> MONTHLY
            "YEARLY", "YEAR", "ANNUALLY" -> YEARLY
            else -> null
        }
    }
}