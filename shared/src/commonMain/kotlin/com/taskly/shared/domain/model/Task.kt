package com.taskly.shared.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Задача (SPEC F-1). Повтор хранится как RRULE-строка (RFC 5545) + режим every/after.
 *
 * Денормализованные поля [tagNames] и [subtaskCount] заполняет клиент (локальная БД
 * с join'ами) для исполнения предикатов языка запросов; сервер может их опускать
 * (дефолты) — sync-дельты передают только собственные поля сущности.
 */
@Serializable
data class Task(
    val id: Uuid,
    val name: String,
    val listId: Uuid? = null,
    /** parentId допустим в модели; подзадачи (реализация) — вне прогона (SPEC Non-goals). */
    val parentId: Uuid? = null,
    val priority: Priority = Priority.NONE,
    val dueAt: Instant? = null,
    val dueHasTime: Boolean = false,
    val startAt: Instant? = null,
    val estimateMin: Int? = null,
    /** RRULE (RFC 5545), например "FREQ=WEEKLY;INTERVAL=1". */
    val rrule: String? = null,
    val repeatMode: RepeatMode? = null,
    val url: String? = null,
    val locationId: Uuid? = null,
    val completedAt: Instant? = null,
    val postponedCount: Int = 0,
    val tagNames: List<String> = emptyList(),
    val subtaskCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val version: Long = 0,
) {
    val isCompleted: Boolean get() = completedAt != null
    val isRepeating: Boolean get() = rrule != null
}