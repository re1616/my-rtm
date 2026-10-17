package com.taskly.shared.sync.protocol

import com.taskly.shared.domain.model.Uuid
import com.taskly.shared.sharedJson
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Протокол синхронизации (SPEC F-5, AC-4): DTO-операции и дельты, field versions,
 * правила слияния из ARCH 5.3.
 */
class SyncProtocolTest {

    private val opId = Uuid.fromString("0192f7c4-5b1e-7a2c-9e3d-1f2a3b4c5d6e")
    private val entityId = Uuid.fromString("0192f7c4-5b1e-7a22-9e3d-0000000000aa")
    private val ts = Instant.parse("2026-10-17T10:42:11.120Z")

    private fun op(
        type: OpType,
        fields: Map<String, String> = emptyMap(),
        base: Long = 0,
    ) = SyncOperation(
        opId = opId,
        scope = "user:0192f7c4-5b1e-7a00-9e3d-0000000000ff",
        entity = EntityType.TASK,
        entityId = entityId,
        type = type,
        fields = fields.mapValues { JsonPrimitive(it.value) },
        baseVersion = base,
        clientTs = ts,
        deviceId = "android-5c1e",
    )

    // ------------------------------------------------------------------ сериализация ----

    @Test
    fun operationRoundtrip() {
        val o = op(OpType.UPDATE, mapOf("due_at" to "2026-10-20T17:00:00+03:00", "priority" to "1"), base = 14)
        val json = sharedJson.encodeToString(o)
        // ARCH 5.1: имена полей snake_case, type/entity как строки
        assertTrue(json.contains("\"op_id\""), json)
        assertTrue(json.contains("\"entity\":\"task\""), json)
        assertTrue(json.contains("\"type\":\"update\""), json)
        assertTrue(json.contains("\"fields\":{\"due_at\":\"2026-10-20T17:00:00+03:00\",\"priority\":\"1\"}"), json)
        assertTrue(json.contains("\"base_version\":14"), json)
        val d = sharedJson.decodeFromString<SyncOperation>(json)
        assertEquals(o, d)
    }

    @Test
    fun batchRoundtrip() {
        val batch = SyncBatch(
            listOf(
                op(OpType.CREATE, mapOf("name" to "Купить молоко", "list_id" to "0192f7c4-5b1e-7a03-9e3d-000000000003")),
                op(OpType.TAG_ADD, mapOf("tag" to "покупки")),
                op(OpType.COMPLETE),
            )
        )
        val json = sharedJson.encodeToString(batch)
        assertEquals(3, sharedJson.decodeFromString<SyncBatch>(json).ops.size)
    }

    @Test
    fun deltaRoundtrip() {
        val delta = SyncDelta(
            scopeId = "user:0192f7c4-5b1e-7a00-9e3d-0000000000ff",
            cursor = 42,
            hasMore = true,
            items = listOf(
                ChangeLogEntry(
                    seq = 42,
                    scopeId = "user:0192f7c4-5b1e-7a00-9e3d-0000000000ff",
                    entity = EntityType.TASK,
                    entityId = entityId,
                    opType = OpType.UPDATE,
                    payload = buildJsonObject { put("name", JsonPrimitive("Купить молоко")) },
                    actorId = Uuid.fromString("0192f7c4-5b1e-7a01-9e3d-0000000000aa"),
                    ts = ts,
                ),
            ),
        )
        val json = sharedJson.encodeToString(delta)
        val back = sharedJson.decodeFromString<SyncDelta>(json)
        assertEquals(delta, back)
        assertEquals("user:0192f7c4-5b1e-7a00-9e3d-0000000000ff", back.scopeId)
        assertEquals(42, back.cursor)
        assertTrue(back.items[0].payload["name"]?.jsonPrimitive?.content == "Купить молоко")
    }

    @Test
    fun fieldVersionsRoundtrip() {
        val v = FieldVersions.of("name" to 3, "due_at" to 5)
        val json = sharedJson.encodeToString(v)
        val back = sharedJson.decodeFromString<FieldVersions>(json)
        assertEquals(v, back)
        assertEquals(3, back.versionOf("name"))
        assertEquals(0, back.versionOf("unknown"))
        assertEquals(FieldVersions.of("name" to 4, "due_at" to 5), v.bumped("name"))
        assertEquals(
            FieldVersions.of("name" to 7, "due_at" to 5),
            v.mergedWith(FieldVersions.of("name" to 7, "due_at" to 2)),
        )
    }

    // ------------------------------------------------------------------ слияние (ARCH 5.3) ----

    @Test
    fun differentFieldsBothSurvive() {
        // Разные поля изменены на двух устройствах — сохраняются оба
        val s0 = EntityState()
        val a = MergeEngine.apply(s0, op(OpType.UPDATE, mapOf("name" to "Отчёт Q3"), base = 14), ts)
        val b = MergeEngine.apply(a.state, op(OpType.UPDATE, mapOf("priority" to "1"), base = 14), ts)
        assertEquals("Отчёт Q3", b.state.fieldString("name"))
        assertEquals("1", b.state.fieldString("priority"))
        assertTrue(b.applied)
    }

    @Test
    fun sameFieldLaterServerWriteWins() {
        // Одно поле изменено дважды — побеждает позднее по серверному порядку (LWW по base_version)
        val s0 = EntityState()
        val a = MergeEngine.apply(s0, op(OpType.UPDATE, mapOf("name" to "v1"), base = 14), ts)
        // клиент B не видел: его base_version всё ещё 14, сервер уже на 15 → поле отклоняется
        val b = MergeEngine.apply(a.state, op(OpType.UPDATE, mapOf("name" to "v2"), base = 14), ts)
        assertEquals("v1", b.state.fieldString("name"))
        assertEquals(listOf("name"), b.skippedFields)
        assertFalse(b.applied)
        // следующий апдейт с актуальной версией применяется
        val c = MergeEngine.apply(a.state, op(OpType.UPDATE, mapOf("name" to "v3"), base = 15), ts)
        assertEquals("v3", c.state.fieldString("name"))
    }

    @Test
    fun completePlusRenameBothApplied() {
        // Одно устройство завершило, другое переименовало — задача завершена И переименована
        val s0 = EntityState(fields = mapOf("name" to JsonPrimitive("Старая")), fieldVersions = FieldVersions.of("name" to 1))
        val renamed = MergeEngine.apply(s0, op(OpType.UPDATE, mapOf("name" to "Новая"), base = 1), ts)
        val completed = MergeEngine.apply(renamed.state, op(OpType.COMPLETE, base = 1), ts)
        assertEquals("Новая", completed.state.fieldString("name"))
        assertNotNull(completed.state.fieldString("completed_at"))
        assertTrue(completed.newlyCompleted)
    }

    @Test
    fun duplicateCompletionCreatesNoSecondInstance() {
        // Повторяющаяся задача завершена на двух устройствах офлайн — второй инстанс не создаётся
        val s0 = EntityState(fields = mapOf("rrule" to JsonPrimitive("FREQ=WEEKLY")))
        val first = MergeEngine.apply(s0, op(OpType.COMPLETE), ts)
        assertTrue(first.newlyCompleted)
        assertTrue(MergeEngine.shouldCreateNextInstance(s0, first.state))
        val second = MergeEngine.apply(first.state, op(OpType.COMPLETE), ts)
        assertFalse(second.applied)
        assertFalse(second.newlyCompleted)
        assertFalse(MergeEngine.shouldCreateNextInstance(first.state, second.state))
    }

    @Test
    fun deleteWinsOverEdit() {
        // Правка удалённой задачи применяется, но задача остаётся в корзине (удаление побеждает)
        val s0 = EntityState(fields = mapOf("name" to JsonPrimitive("Задача")))
        val deleted = MergeEngine.apply(s0, op(OpType.DELETE), ts)
        assertTrue(deleted.state.deleted)
        val edit = MergeEngine.apply(deleted.state, op(OpType.UPDATE, mapOf("name" to "Правка"), base = 0), ts)
        assertEquals("Правка", edit.state.fieldString("name"))
        assertTrue(edit.state.deleted, "удаление не снимается правкой")
        // повторный delete — идемпотентно
        val del2 = MergeEngine.apply(deleted.state, op(OpType.DELETE), ts)
        assertFalse(del2.applied)
    }

    @Test
    fun tagAddAndRemove() {
        val s0 = EntityState(fields = mapOf("tags" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))))
        val add = MergeEngine.apply(s0, op(OpType.TAG_ADD, mapOf("tag" to "c")), ts)
        val tags = add.state.fields["tags"] as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("a", "b", "c"), tags.map { (it as JsonPrimitive).content })
        // повторное добавление — без изменений
        val dup = MergeEngine.apply(add.state, op(OpType.TAG_ADD, mapOf("tag" to "c")), ts)
        assertFalse(dup.applied)
        val rm = MergeEngine.apply(add.state, op(OpType.TAG_REMOVE, mapOf("tag" to "b")), ts)
        val tags2 = rm.state.fields["tags"] as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("a", "c"), tags2.map { (it as JsonPrimitive).content })
    }

    @Test
    fun uncompleteClearsCompletion() {
        val s0 = EntityState(fields = mapOf("completed_at" to JsonPrimitive(ts.toString())))
        val r = MergeEngine.apply(s0, op(OpType.UNCOMPLETE), ts)
        assertTrue(r.applied)
        assertEquals(null, r.state.fieldString("completed_at"))
    }

    @Test
    fun createIsIdempotent() {
        // повторная отправка CREATE с тем же op_id не хуже merge-update
        val fields = mapOf("name" to "Задача", "list_id" to "0192f7c4-5b1e-7a03-9e3d-000000000003")
        val a = MergeEngine.apply(EntityState(), op(OpType.CREATE, fields), ts)
        val b = MergeEngine.apply(a.state, op(OpType.CREATE, fields), ts)
        assertEquals(a.state.fields, b.state.fields)
    }
}