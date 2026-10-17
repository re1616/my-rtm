package com.taskly.shared.sync.protocol

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Состояние сущности на сервере: значения полей + версии полей (LWW) + флаг удаления.
 * Поля — snake_case (как в payload операций и change_log).
 */
data class EntityState(
    val fields: Map<String, JsonElement> = emptyMap(),
    val fieldVersions: FieldVersions = FieldVersions(),
    val deleted: Boolean = false,
) {
    fun field(name: String): JsonElement? = fields[name]
    fun fieldString(name: String): String? = (fields[name] as? JsonPrimitive)?.contentOrNull
}

/** Результат применения операции. */
data class MergeResult(
    val state: EntityState,
    /** Операция что-то изменила (для идемпотентности и аудита). */
    val applied: Boolean,
    /** Переход не-выполнена → выполнена (только тогда сервер создаёт следующий повтор). */
    val newlyCompleted: Boolean,
    /** Поля, отклонённые LWW (изменены на сервере после base_version). */
    val skippedFields: List<String>,
)

/**
 * Слияние операций синхронизации (ARCH 5.3):
 * - разные поля двух устройств сохраняются оба (LWW по полям);
 * - одно поле изменено дважды — побеждает позднее по серверному порядку (base_version);
 * - завершение + переименование — применяются оба;
 * - повторное завершение повтора не создаёт второй экземпляр (newlyCompleted=false);
 * - правка удалённой задачи применяется, но задача остаётся удалённой (удаление побеждает).
 */
object MergeEngine {

    fun apply(existing: EntityState, op: SyncOperation, serverTs: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)): MergeResult {
        var fields = existing.fields.toMutableMap()
        var versions = existing.fieldVersions
        val skipped = mutableListOf<String>()
        var applied = false
        var newlyCompleted = false

        when (op.type) {
            OpType.DELETE -> {
                if (!existing.deleted) {
                    applied = true
                    versions = versions.bumped("deleted")
                }
                return MergeResult(
                    EntityState(fields, versions, deleted = true),
                    applied,
                    newlyCompleted = false,
                    skipped,
                )
            }

            OpType.CREATE, OpType.UPDATE, OpType.MOVE -> {
                for ((k, v) in op.fields) {
                    val fieldVersion = versions.versionOf(k)
                    if (op.baseVersion < fieldVersion) {
                        // Поле изменено на сервере после base_version клиента — отклоняем (LWW)
                        skipped += k
                        continue
                    }
                    fields[k] = v
                    versions = versions.bumpedTo(k, maxOf(fieldVersion, op.baseVersion) + 1)
                    applied = true
                }
            }

            OpType.COMPLETE -> {
                val wasCompleted = fields["completed_at"] != null
                if (!wasCompleted) {
                    val ts = (op.fields["completed_at"] as? JsonPrimitive)?.contentOrNull
                        ?: serverTs.toString()
                    fields["completed_at"] = JsonPrimitive(ts)
                    versions = versions.bumped("completed_at")
                    newlyCompleted = true
                    applied = true
                }
            }

            OpType.UNCOMPLETE -> {
                if (fields["completed_at"] != null) {
                    fields.remove("completed_at")
                    versions = versions.bumped("completed_at")
                    applied = true
                }
            }

            OpType.TAG_ADD, OpType.TAG_REMOVE -> {
                val tagName = op.fields["tag"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return MergeResult(existing, false, false, skipped)
                val tags = tagList(fields["tags"])
                val updated = if (op.type == OpType.TAG_ADD) {
                    if (tags.contains(tagName)) tags else tags + tagName
                } else {
                    tags.filter { it != tagName }
                }
                if (updated != tags) {
                    fields["tags"] = JsonArray(updated.map { JsonPrimitive(it) })
                    versions = versions.bumped("tags")
                    applied = true
                }
            }
        }

        val deleted = existing.deleted // удаление не снимается правкой (побеждает удаление)
        return MergeResult(EntityState(fields, versions, deleted), applied, newlyCompleted, skipped)
    }

    /**
     * Сервер создаёт следующий экземпляр повтора только при переходе не-выполнена →
     * выполнена (SPEC F-4, ARCH 5.3: «дубль завершения повтора не создаёт второй экземпляр»).
     */
    fun shouldCreateNextInstance(previous: EntityState, merged: EntityState): Boolean =
        previous.field("completed_at") == null && merged.field("completed_at") != null

    private fun tagList(element: JsonElement?): List<String> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}