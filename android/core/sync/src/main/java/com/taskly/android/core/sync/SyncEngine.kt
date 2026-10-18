package com.taskly.android.core.sync

import android.content.Context
import androidx.work.*
import com.taskly.android.core.database.TasklyDatabase

/**
 * Phase 1 scaffold: sync engine stub. Real implementation will use
 * Ktor client + WorkManager + Room in coder phase (AND-S2, AND-S3, AND-S4).
 */
object SyncEngine {
    suspend fun enqueueOperation(
        db: TasklyDatabase,
        entityType: String,
        entityId: String,
        operationType: String,
        fields: Map<String, Any?>,
        userId: String
    ) {
        // stub — real implementation in coder phase
    }

    suspend fun processQueue(
        db: TasklyDatabase,
        accessToken: String
    ): Int = 0
}

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return Result.success()
    }
}