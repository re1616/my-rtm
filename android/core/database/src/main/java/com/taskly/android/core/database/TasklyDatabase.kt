package com.taskly.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        ListEntity::class,
        TagEntity::class,
        TaskTagEntity::class,
        FieldVersionEntity::class,
        SyncOperationEntity::class,
        NoteEntity::class,
        AttachmentEntity::class,
        LocationEntity::class,
        ConflictEntity::class,
        TaskSearchEntity::class,
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TasklyDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun listDao(): ListDao
    abstract fun tagDao(): TagDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun fieldVersionDao(): FieldVersionDao
    abstract fun noteDao(): NoteDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun locationDao(): LocationDao
    abstract fun conflictDao(): ConflictDao
    abstract fun searchDao(): SearchDao

    companion object {
        // v1 → v2: add tasks.sort_order (client-side per-list manual ordering)
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 → v3 (M4): locations on tasks, notes, attachments, locations, conflicts, FTS search
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN location_id TEXT")
                db.execSQL("ALTER TABLE sync_queue ADD COLUMN base_version INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `task_id` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, `created_at` TEXT NOT NULL, `updated_at` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_task_id` ON `notes` (`task_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachments` (`id` TEXT NOT NULL, `task_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, `created_at` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_task_id` ON `attachments` (`task_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `locations` (`id` TEXT NOT NULL, `owner_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`created_at` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conflicts` (`entity_type` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `field` TEXT NOT NULL, " +
                        "`local_value` TEXT, `server_value` TEXT, `created_at` TEXT NOT NULL, PRIMARY KEY(`entity_id`, `field`))"
                )
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `task_search` USING FTS4(`task_id` TEXT NOT NULL, `text` TEXT NOT NULL, tokenize=unicode61)")
                // Existing tasks become searchable at once
                db.execSQL("INSERT INTO task_search (task_id, text) SELECT id, name FROM tasks WHERE deleted_at IS NULL")
            }
        }

        @Volatile
        private var INSTANCE: TasklyDatabase? = null

        fun getInstance(context: Context): TasklyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TasklyDatabase::class.java,
                    "taskly.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}