package com.taskly.shared

/**
 * Текущее время в миллисекундах с эпохи (UTC).
 * Platform-specific: JVM — System.currentTimeMillis(), JS — Date.now().
 * Не зависит от kotlinx-datetime/kotlin.time Clock, чтобы избежать API-хрупкости.
 */
internal expect fun currentEpochMillis(): Long