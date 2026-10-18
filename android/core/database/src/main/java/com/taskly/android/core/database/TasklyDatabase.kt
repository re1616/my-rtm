package com.taskly.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        TaskEntity::class,
        ListEntity::class,
        TagEntity::class,
        TaskTagEntity::class,
        FieldVersionEntity::class,
        SyncOperationEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class TasklyDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun listDao(): ListDao
    abstract fun tagDao(): TagDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun fieldVersionDao(): FieldVersionDao

    companion object {
        @Volatile
        private var INSTANCE: TasklyDatabase? = null

        fun getInstance(context: Context): TasklyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TasklyDatabase::class.java,
                    "taskly.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}