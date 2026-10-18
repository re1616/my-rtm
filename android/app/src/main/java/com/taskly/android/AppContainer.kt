package com.taskly.android

import android.content.Context
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.TasklyApiClient

class AppContainer(context: Context) {
    val database: TasklyDatabase = TasklyDatabase.getInstance(context)
    val apiClient: TasklyApiClient = TasklyApiClient
}