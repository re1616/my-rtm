package com.taskly.android.feature.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.core.sync.SyncSession
import com.taskly.android.core.sync.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Task list state. The UI reads tasks from Room only (local-first); every mutation is written
 * together with its sync operation and a sync is requested — WorkManager runs it immediately
 * when online, or as soon as the network comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val database: TasklyDatabase,
    private val auth: AuthRepository,
    private val tasks: TaskRepository,
    private val sync: SyncEngine,
    private val session: SyncSession,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val userId = MutableStateFlow(auth.getUserId())

    val isLoggedIn: StateFlow<Boolean> = userId
        .map { it != null && auth.isLoggedIn() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, auth.isLoggedIn())

    val taskList: StateFlow<List<TaskEntity>> = userId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else database.taskDao().getAll(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        if (auth.isLoggedIn()) refresh()
    }

    fun login(email: String, password: String, register: Boolean) = viewModelScope.launch {
        _error.value = null
        val result = if (register) auth.register(email, password) else auth.login(email, password)
        result
            .onSuccess {
                userId.value = auth.getUserId()
                refresh()
            }
            .onFailure { _error.value = it.message ?: "Authentication failed" }
    }

    fun createTask(name: String) = viewModelScope.launch {
        val id = userId.value ?: return@launch
        tasks.createTask(id, name.trim())
        SyncEngine.requestSync(context)
    }

    fun setCompleted(task: TaskEntity, completed: Boolean) = viewModelScope.launch {
        val id = userId.value ?: return@launch
        tasks.setCompleted(task, id, completed)
        SyncEngine.requestSync(context)
    }

    /** Push the queue and pull remote changes now; failures leave the queue for the next attempt. */
    fun refresh() = viewModelScope.launch {
        val id = userId.value ?: return@launch
        val token = auth.getAccessToken() ?: return@launch
        _syncing.value = true
        runCatching { session.cursor = sync.syncNow(id, token, session.cursor) }
            .onFailure { SyncEngine.requestSync(context) }
        _syncing.value = false
    }
}
