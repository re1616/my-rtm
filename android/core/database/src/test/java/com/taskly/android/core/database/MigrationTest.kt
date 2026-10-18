package com.taskly.android.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private lateinit var database: TasklyDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TasklyDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun databaseIsCreatedSuccessfully() {
        assertNotNull(database)
        assertNotNull(database.taskDao())
        assertNotNull(database.listDao())
        assertNotNull(database.tagDao())
        assertNotNull(database.syncQueueDao())
        assertNotNull(database.fieldVersionDao())
    }
}