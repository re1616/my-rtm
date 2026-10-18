package com.taskly.android

import android.content.Context
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.TasklyApiClient
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.feature.tasks.AuthRepository

class AppContainer(context: Context) {
    val database: TasklyDatabase = TasklyDatabase.getInstance(context)
    val apiClient: TasklyApiClient = TasklyApiClient.create()
    val authRepository: AuthRepository = AuthRepository(apiClient, database)
    val syncEngine: SyncEngine = SyncEngine
}