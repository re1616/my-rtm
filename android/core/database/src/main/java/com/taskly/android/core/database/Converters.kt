package com.taskly.android.core.database

/**
 * Type converters — will be used with Room @TypeConverters in Phase 2.
 * Phase 1 scaffold: no-op.
 */
class Converters {
    fun fromStringList(value: List<String>?): String? = value?.joinToString(",")
    fun toStringList(value: String?): List<String>? = value?.split(",")?.filter { it.isNotBlank() }
}