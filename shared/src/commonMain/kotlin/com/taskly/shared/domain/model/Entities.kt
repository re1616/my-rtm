package com.taskly.shared.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Доменные сущности Taskly (SPEC F-1, docs/original/SPEC_taskly.md §2).
 * Все сущности: UUIDv7 id, createdAt/updatedAt/deletedAt (мягкое удаление), version.
 * Сериализуются kotlinx.serialization (JSON) — используются сервером и sync-протоколом.
 */

@Serializable
data class User(
    val id: Uuid,
    val email: String,
    val name: String = "",
    val timezone: String = "Europe/Moscow",
    val locale: String = "ru",
    val dateFormat: String = "dd.MM.yyyy",
    val firstDayOfWeek: Int = 1,
    val plan: String = "free",
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val version: Long = 0,
)

@Serializable
data class TaskList(
    val id: Uuid,
    val name: String,
    val ownerId: Uuid? = null,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val isSmart: Boolean = false,
    /** Язык запросов умных списков (F-3) для is_smart=true. */
    val filterQuery: String? = null,
    val defaultDue: String? = null,
    val color: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val version: Long = 0,
)

@Serializable
data class Tag(
    val id: Uuid,
    val name: String,
    val color: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val version: Long = 0,
)

@Serializable
data class Note(
    val id: Uuid,
    val taskId: Uuid,
    val title: String = "",
    val body: String = "",
    val authorId: Uuid? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val version: Long = 0,
)

@Serializable
data class Location(
    val id: Uuid,
    val name: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val address: String? = null,
    val radiusM: Int? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val version: Long = 0,
)

/** Напоминание; поля — каркас, доставка (push/email/web) вне прогона (SPEC Non-goals). */
@Serializable
data class Reminder(
    val id: Uuid,
    val taskId: Uuid,
    /** offsetMin != null — относительное напоминание; иначе абсолютное fireAt. */
    val offsetMin: Int? = null,
    val fireAt: Instant? = null,
    val channel: String = "push",
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val version: Long = 0,
)

/** Умный список = TaskList с isSmart=true; хранит строку запроса (SPEC F-3). */
@Serializable
data class SavedSearch(
    val id: Uuid,
    val name: String,
    val query: String,
    val ownerId: Uuid? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val version: Long = 0,
)