package com.taskly.android.feature.tasks

import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.sync.SyncEngine

/**
 * ViewModel for the task list screen.
 * Wraps the database, auth, and sync layers behind a clean interface.
 *
 * Hilt readiness: when Hilt codegen is active, this class gets
 * @HiltViewModel and @Inject constructor annotations for automatic DI.
 */
class TaskListViewModel(
    private val database: TasklyDatabase,
    private val authRepository: AuthRepository,
    private val syncEngine: SyncEngine
) {
    private val taskRepository = TaskRepository(database)

    fun taskDao() = database.taskDao()
    fun repository() = taskRepository
    fun auth() = authRepository
    fun sync() = syncEngine
    fun getUserId(): String? = authRepository.getUserId()

    suspend fun syncNow() {
        val token = authRepository.getAccessToken() ?: return
        val userId = authRepository.getUserId() ?: return
        syncEngine.processQueue(database, token)
        syncEngine.pullDeltas(database, userId, 0L, token)
    }
}