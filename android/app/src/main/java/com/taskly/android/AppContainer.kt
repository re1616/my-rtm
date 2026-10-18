package com.taskly.android

import android.content.Context
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.TasklyApiClient
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.feature.tasks.AuthRepository

/**
 * Manual DI container — replaces Hilt modules.
 *
 * When Hilt codegen is compatible with Kotlin 2.4+, this class is
 * removed and replaced by Hilt @Module classes in the same package.
 */
class AppContainer(context: Context) {
    val database: TasklyDatabase = TasklyDatabase.getInstance(context)
    val apiClient: TasklyApiClient = TasklyApiClient.create()
    val authRepository: AuthRepository = AuthRepository(apiClient, database, context)
    val syncEngine: SyncEngine = SyncEngine
}