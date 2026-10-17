package com.taskly.shared.sync.protocol

import com.taskly.shared.domain.model.Uuid
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Типы сущностей sync-протокола (SPEC F-5). */
@Serializable
enum class EntityType {
    @SerialName("task")
    TASK,

    @SerialName("list")
    LIST,

    @SerialName("tag")
    TAG,

    @SerialName("note")
    NOTE,

    @SerialName("location")
    LOCATION,

    @SerialName("reminder")
    REMINDER,

    @SerialName("saved_search")
    SAVED_SEARCH,

    @SerialName("user")
    USER,
}

/** Типы идемпотентных операций (ARCH 5.1). */
@Serializable
enum class OpType {
    @SerialName("create")
    CREATE,

    @SerialName("update")
    UPDATE,

    @SerialName("delete")
    DELETE,

    @SerialName("complete")
    COMPLETE,

    @SerialName("uncomplete")
    UNCOMPLETE,

    @SerialName("move")
    MOVE,

    @SerialName("tag_add")
    TAG_ADD,

    @SerialName("tag_remove")
    TAG_REMOVE,
}

/**
 * Операция синхронизации (ARCH 5.1): идемпотентна по [opId], несёт только изменённые
 * поля ([fields]), базовую версию для LWW-слияния и время клиента.
 * JSON-имена — snake_case, как в ARCH-примере.
 */
@Serializable
data class SyncOperation(
    @SerialName("op_id")
    val opId: Uuid,
    /** Пространство синхронизации: "user:<uuid>" или "list:<uuid>" (общие списки). */
    val scope: String,
    val entity: EntityType,
    @SerialName("entity_id")
    val entityId: Uuid,
    val type: OpType,
    /** Изменённые поля (snake_case, как в ARCH-примере). */
    val fields: Map<String, JsonElement> = emptyMap(),
    @SerialName("base_version")
    val baseVersion: Long = 0,
    @SerialName("client_ts")
    val clientTs: Instant,
    @SerialName("device_id")
    val deviceId: String,
)

/** Пакет операций клиента (до 500 штук, SPEC F-5). */
@Serializable
data class SyncBatch(
    val ops: List<SyncOperation>,
)

/** Запись серверного change_log — элемент дельты (ARCH 4.3). */
@Serializable
data class ChangeLogEntry(
    val seq: Long,
    @SerialName("scope_id")
    val scopeId: String,
    val entity: EntityType,
    @SerialName("entity_id")
    val entityId: Uuid,
    @SerialName("op_type")
    val opType: OpType,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("actor_id")
    val actorId: Uuid? = null,
    val ts: Instant,
)

/** Курсорная дельта: изменения после cursor (SPEC F-5). */
@Serializable
data class SyncDelta(
    @SerialName("scope_id")
    val scopeId: String,
    val cursor: Long,
    val items: List<ChangeLogEntry>,
    @SerialName("has_more")
    val hasMore: Boolean = false,
)

/**
 * Версии полей сущности для LWW-слияния (ARCH 4.3 `field_versions jsonb`).
 * Монотонно растут по мере применения операций на сервере (серверный seq-порядок).
 */
@Serializable
data class FieldVersions(
    val versions: Map<String, Long> = emptyMap(),
) {
    fun versionOf(field: String): Long = versions[field] ?: 0L

    fun bumped(field: String): FieldVersions = FieldVersions(versions + (field to (versionOf(field) + 1)))

    /** Установить версию поля в новое значение (LWW по base_version операции). */
    fun bumpedTo(field: String, value: Long): FieldVersions = FieldVersions(versions + (field to value))

    /** Объединение: максимум по каждому полю. */
    fun mergedWith(other: FieldVersions): FieldVersions {
        val out = versions.toMutableMap()
        for ((k, v) in other.versions) out[k] = maxOf(out[k] ?: 0L, v)
        return FieldVersions(out)
    }

    companion object {
        fun of(vararg pairs: Pair<String, Long>): FieldVersions = FieldVersions(pairs.toMap())
    }
}