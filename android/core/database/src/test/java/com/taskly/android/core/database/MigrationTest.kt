package com.taskly.android.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Migration test: v1 → v2 (adds tasks.sort_order column).
 *
 * Runs on the JVM via Robolectric (no emulator needed, CI-safe).
 * v1 schema is created manually with raw SQL (the same layout that
 * shipped before the sort_order column existed); then
 * [TasklyDatabase.MIGRATION_1_2] is run and validated against the
 * exported v2 schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TasklyDatabase::class.java,
        emptyList()
    )

    @Test
    fun migration1To2_preservesData_andAddsSortOrderColumn() {
        // Sanity: exported v2 schema must exist for runMigrationsAndValidate
        ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Create a v1 database with the original schema (no sort_order).
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT NOT NULL PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    list_id TEXT NOT NULL,
                    parent_id TEXT,
                    name TEXT NOT NULL,
                    priority INTEGER,
                    due_at TEXT,
                    due_has_time INTEGER NOT NULL,
                    start_at TEXT,
                    estimate_min INTEGER,
                    rrule TEXT,
                    repeat_mode TEXT,
                    completed_at TEXT,
                    postponed_count INTEGER NOT NULL,
                    url TEXT,
                    version INTEGER NOT NULL,
                    deleted_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_lists (
                    id TEXT NOT NULL PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    is_smart INTEGER NOT NULL,
                    query TEXT,
                    color TEXT,
                    archived INTEGER NOT NULL,
                    sort_order INTEGER NOT NULL,
                    version INTEGER NOT NULL,
                    deleted_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS tags (
                    id TEXT NOT NULL PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS task_tags (
                    task_id TEXT NOT NULL,
                    tag_id TEXT NOT NULL,
                    PRIMARY KEY (task_id, tag_id)
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS field_versions (
                    entity_id TEXT NOT NULL,
                    field_name TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    value TEXT,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (entity_id, field_name)
                )
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_queue (
                    opId TEXT NOT NULL PRIMARY KEY,
                    entity_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    operation_type TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    fields TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    retry_count INTEGER NOT NULL,
                    last_error TEXT,
                    status TEXT NOT NULL
                )
                """.trimIndent()
            )

            // Seed one task so we prove data survives the migration.
            execSQL(
                """
                INSERT INTO tasks (
                    id, owner_id, list_id, parent_id, name, priority,
                    due_has_time, postponed_count, version, created_at, updated_at
                ) VALUES (
                    'task-1', 'user-1', 'list-1', NULL, 'Migrated task', 1,
                    0, 0, 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'
                )
                """.trimIndent()
            )
            close()
        }

        // 2. Run v1 → v2 migration and validate against the exported v2 schema.
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, TasklyDatabase.MIGRATION_1_2)

        // 3. Data must survive and the new column must exist with its default value.
        db.query("SELECT id, name, sort_order FROM tasks WHERE id = 'task-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "Expected seeded task to survive migration" }
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            val sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow("sort_order"))
            assert(name == "Migrated task") { "Unexpected name: $name" }
            assert(sortOrder == 0) { "Expected default sort_order=0, got $sortOrder" }
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}