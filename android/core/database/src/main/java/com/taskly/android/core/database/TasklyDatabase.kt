package com.taskly.android.core.database

import android.content.Context

/**
 * Database class — stubbed for Phase 1 scaffold.
 * Room + Hilt + KSP will replace these in coder phase (AND-S1).
 */
class TasklyDatabase {
    private val _taskDao = TaskDao()
    private val _listDao = ListDao()
    private val _tagDao = TagDao()
    private val _syncQueueDao = SyncQueueDao()
    private val _fieldVersionDao = FieldVersionDao()

    fun taskDao(): TaskDao = _taskDao
    fun listDao(): ListDao = _listDao
    fun tagDao(): TagDao = _tagDao
    fun syncQueueDao(): SyncQueueDao = _syncQueueDao
    fun fieldVersionDao(): FieldVersionDao = _fieldVersionDao

    companion object {
        @Volatile
        private var INSTANCE: TasklyDatabase? = null

        fun getInstance(context: Context): TasklyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = TasklyDatabase()
                INSTANCE = instance
                instance
            }
        }
    }
}