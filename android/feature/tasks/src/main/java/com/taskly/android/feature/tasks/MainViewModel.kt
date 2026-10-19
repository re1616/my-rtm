package com.taskly.android.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskly.android.core.database.AttachmentEntity
import com.taskly.android.core.network.AccountSettingsDto
import kotlinx.serialization.json.JsonObject
import com.taskly.android.core.database.ConflictEntity
import com.taskly.android.core.database.ListEntity
import com.taskly.android.core.database.LocationEntity
import com.taskly.android.core.database.NoteEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.sync.SmartLists
import com.taskly.android.core.sync.SyncStatus
import com.taskly.android.core.sync.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the task list shows. */
sealed interface TaskView {
    enum class System { INBOX, TODAY, TOMORROW, WEEK, OVERDUE, ALL, COMPLETED }

    data class Sys(val kind: System) : TaskView
    data class OfList(val listId: String) : TaskView
    data class Smart(val listId: String) : TaskView
    data class Tag(val name: String) : TaskView
    data class Search(val text: String) : TaskView
}

/** A change that can be undone from the snackbar (SPEC FR-6). */
data class UndoEvent(val message: Int, val undo: suspend () -> Unit)

/**
 * Task screens state (UDF, ARCH: Compose → ViewModel → repositories → Room). The UI reads only
 * Room flows; every change goes through [TaskRepository] (local write + sync op in one
 * transaction) and asks for a sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val db: TasklyDatabase,
    private val repo: TaskRepository,
    private val auth: AuthRepository,
    syncStatus: StateFlow<SyncStatus>,
    private val requestSync: () -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
    /** Account settings changed: cache them for on-device alarms and reschedule. */
    private val onSettings: (AccountSettingsDto) -> Unit = {},
) : ViewModel() {

    enum class Screen { TASKS, SETTINGS }

    val screen = MutableStateFlow(Screen.TASKS)
    val settings = MutableStateFlow<AccountSettingsDto?>(null)
    val settingsError = MutableStateFlow(false)
    val resetSent = MutableStateFlow(false)

    val userId = MutableStateFlow(auth.getUserId())
    val view = MutableStateFlow<TaskView>(TaskView.Sys(TaskView.System.INBOX))
    val showCompleted = MutableStateFlow(false)
    val openTaskId = MutableStateFlow<String?>(null)
    val status: StateFlow<SyncStatus> = syncStatus

    private val _undo = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val undo: SharedFlow<UndoEvent> = _undo.asSharedFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** Login needs the authenticator code (two-factor auth). */
    val needsTotp = MutableStateFlow(false)

    private fun <T> userFlow(empty: T, block: (String) -> Flow<T>): StateFlow<T> =
        userId.flatMapLatest { id -> if (id == null) flowOf(empty) else block(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, empty)

    val allTasks: StateFlow<List<TaskEntity>> = userFlow(emptyList()) { db.taskDao().getAll(it) }
    val lists: StateFlow<List<ListEntity>> = userFlow(emptyList()) { db.listDao().getAll(it) }
    val locations: StateFlow<List<LocationEntity>> = userFlow(emptyList()) { db.locationDao().getAll(it) }
    val conflicts: StateFlow<List<ConflictEntity>> = db.conflictDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingChanges: StateFlow<Int> = db.syncQueueDao().pendingCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** The server keeps the inbox as a list named "Inbox"; locally it is "Входящие". */
    val inboxId: StateFlow<String?> = lists.map { l -> l.firstOrNull { it.name == SERVER_INBOX }?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** User lists shown in the drawer (the server inbox is the "Входящие" view). */
    val userLists: StateFlow<List<ListEntity>> = lists.map { l -> l.filter { !it.isSmart && it.name != SERVER_INBOX && !it.archived } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val smartLists: StateFlow<List<ListEntity>> = lists.map { l -> l.filter { it.isSmart } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Tag links are not part of the task row: refresh on task changes and on every queue change
    val tagNamesByTask: StateFlow<Map<String, List<String>>> = combine(allTasks, pendingChanges) { _, _ -> Unit }.mapLatest {
        db.tagDao().allTaskTagNames().groupBy({ it.taskId }, { it.name })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val tagNames: StateFlow<List<String>> = tagNamesByTask.map { m -> m.values.flatten().distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    data class ListState(val tasks: List<TaskEntity> = emptyList(), val error: String? = null, val tags: Map<String, List<String>> = emptyMap())

    val visible: StateFlow<ListState> = combine(
        combine(allTasks, view, showCompleted) { a, b, c -> Triple(a, b, c) },
        combine(lists, locations, tagNamesByTask, inboxId) { l, loc, tags, inbox -> Quad(l, loc, tags, inbox) },
    ) { (tasks, v, completed), q -> compute(tasks, v, completed, q.a, q.b, q.c, q.d) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListState())

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun compute(
        tasks: List<TaskEntity>, v: TaskView, completedTab: Boolean,
        lists: List<ListEntity>, locations: List<LocationEntity>, tags: Map<String, List<String>>, inbox: String?,
    ): ListState {
        val byTab: (TaskEntity) -> Boolean = { if (completedTab) it.completedAt != null else it.completedAt == null }
        val dueDay: (TaskEntity) -> LocalDate? = { t -> t.dueAt?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() } }
        val d = today()
        val chosen: List<TaskEntity> = when (v) {
            is TaskView.Sys -> when (v.kind) {
                TaskView.System.INBOX -> tasks.filter { (it.listId == TaskRepository.INBOX || it.listId == inbox) && byTab(it) }
                TaskView.System.TODAY -> tasks.filter { it.completedAt == null && dueDay(it) == d }
                TaskView.System.TOMORROW -> tasks.filter { it.completedAt == null && dueDay(it) == d.plusDays(1) }
                TaskView.System.WEEK -> tasks.filter { t -> t.completedAt == null && dueDay(t)?.let { !it.isBefore(d) && it.isBefore(d.plusDays(7)) } == true }
                TaskView.System.OVERDUE -> tasks.filter { t -> t.completedAt == null && dueDay(t)?.isBefore(d) == true }
                TaskView.System.ALL -> tasks.filter(byTab)
                TaskView.System.COMPLETED -> tasks.filter { it.completedAt != null }
            }
            is TaskView.OfList -> tasks.filter { it.listId == v.listId && byTab(it) }
            is TaskView.Tag -> tasks.filter { v.name in tags[it.id].orEmpty() && byTab(it) }
            is TaskView.Smart, is TaskView.Search -> {
                val query = if (v is TaskView.Smart) lists.firstOrNull { it.id == v.listId }?.query.orEmpty() else (v as TaskView.Search).text
                if (query.isBlank()) return ListState(tags = tags)
                SmartLists.problem(query)?.let { problem ->
                    // A plain word is a name search, a query with an error is shown as such
                    if (!query.contains(':')) return ListState(tasks.filter { it.name.contains(query, ignoreCase = true) && byTab(it) }, tags = tags)
                    return ListState(error = problem, tags = tags)
                }
                val listsForQuery = lists.map { if (it.id == inbox) it.copy(name = SERVER_INBOX) else it }
                return ListState(SmartLists.filter(query, tasks, listsForQuery, locations, tags).filter(byTab).sortedWith(ORDER), tags = tags)
            }
        }
        // Subtasks are shown inside their parent unless the parent is not in the view
        val ids = chosen.map { it.id }.toSet()
        return ListState(chosen.filter { it.parentId == null || it.parentId !in ids }.sortedWith(ORDER), tags = tags)
    }

    // ── Task card ──

    val openTask: StateFlow<TaskEntity?> = openTaskId.flatMapLatest { id -> if (id == null) flowOf(null) else db.taskDao().observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val openNotes: StateFlow<List<NoteEntity>> = openTaskId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.noteDao().forTask(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val openAttachments: StateFlow<List<AttachmentEntity>> = openTaskId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.attachmentDao().forTask(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val openSubtasks: StateFlow<List<TaskEntity>> = openTaskId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.taskDao().subtasks(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Auth ──

    fun login(email: String, password: String, register: Boolean, totpCode: String? = null) = viewModelScope.launch {
        _error.value = null
        val result = if (register) auth.register(email, password) else auth.login(email, password, totpCode)
        result
            .onSuccess {
                needsTotp.value = false
                userId.value = auth.getUserId()
                requestSync()
            }
            .onFailure {
                if (auth.isTotpRequired(it)) needsTotp.value = true else _error.value = it.message ?: "Authentication failed"
            }
    }

    fun forgotPassword(email: String) = viewModelScope.launch {
        auth.forgotPassword(email).onSuccess { resetSent.value = true }.onFailure { _error.value = it.message }
    }

    fun clearError() { _error.value = null }

    fun logout() {
        auth.logout()
        userId.value = null
        openTaskId.value = null
        screen.value = Screen.TASKS
    }

    // ── Settings ──

    fun loadSettings() = viewModelScope.launch {
        auth.settings()
            .onSuccess { settings.value = it; settingsError.value = false; onSettings(it) }
            .onFailure { settingsError.value = true }
    }

    fun saveSettings(patch: JsonObject) = viewModelScope.launch {
        auth.updateSettings(patch)
            .onSuccess { settings.value = it; settingsError.value = false; onSettings(it) }
            .onFailure { settingsError.value = true }
    }

    fun select(v: TaskView) {
        view.value = v
        screen.value = Screen.TASKS
        showCompleted.value = false
    }

    // ── Changes ──

    private fun change(block: suspend (String) -> Unit) = viewModelScope.launch {
        val id = userId.value ?: return@launch
        block(id)
        requestSync()
    }

    fun addFromSmartAdd(input: String, parentId: String? = null) = change { uid ->
        val defaultList = when (val v = view.value) {
            is TaskView.OfList -> v.listId
            else -> TaskRepository.INBOX
        }
        val created = repo.createFromSmartAdd(uid, input, defaultList) ?: return@change
        var task = created
        if (parentId != null) task = repo.update(task, uid) { copy(parentId = parentId) }
        // In "Сегодня"/"Завтра" a task without a due date gets that day (as on the web)
        val v = view.value
        if (task.dueAt == null && parentId == null && v is TaskView.Sys && (v.kind == TaskView.System.TODAY || v.kind == TaskView.System.TOMORROW)) {
            val day = today().plusDays(if (v.kind == TaskView.System.TOMORROW) 1 else 0)
            repo.update(task, uid) { copy(dueAt = day.atStartOfDay(zone).toInstant().toString(), dueHasTime = false) }
        }
        _undo.emit(UndoEvent(R.string.undo_added) { repo.delete(task, uid); requestSync() })
    }

    fun toggleComplete(task: TaskEntity) = change { uid ->
        val done = task.completedAt == null
        repo.setCompleted(task, uid, done)
        _undo.emit(UndoEvent(if (done) R.string.undo_completed else R.string.undo_reopened) {
            db.taskDao().getById(task.id)?.let { repo.setCompleted(it, uid, !done) }
            requestSync()
        })
    }

    fun postpone(task: TaskEntity) = change { uid ->
        repo.postpone(task, uid)
        _undo.emit(UndoEvent(R.string.undo_postponed) {
            db.taskDao().getById(task.id)?.let { now -> repo.update(now, uid) { copy(dueAt = task.dueAt, dueHasTime = task.dueHasTime, postponedCount = task.postponedCount) } }
            requestSync()
        })
    }

    fun delete(task: TaskEntity) = change { uid ->
        repo.delete(task, uid)
        if (openTaskId.value == task.id) openTaskId.value = null
        _undo.emit(UndoEvent(R.string.undo_deleted) { repo.restore(task.id, uid); requestSync() })
    }

    fun update(task: TaskEntity, edit: TaskEntity.() -> TaskEntity) = change { uid -> repo.update(task, uid, edit) }

    fun addTag(task: TaskEntity, tag: String) = change { uid -> repo.addTag(task, uid, tag) }
    fun removeTag(task: TaskEntity, tag: String) = change { uid -> repo.removeTag(task, uid, tag) }
    fun addNote(taskId: String, text: String) = change { uid -> repo.addNote(uid, taskId, text) }
    fun editNote(note: NoteEntity, text: String) = change { uid -> repo.updateNote(uid, note, text) }
    fun deleteNote(note: NoteEntity) = change { uid -> repo.deleteNote(uid, note) }
    fun resolve(conflict: ConflictEntity, keepMine: Boolean) = change { uid -> repo.resolveConflict(uid, conflict, keepMine) }

    /** Parses "завтра 17:00" etc. with the shared parser; null when not a date. */
    fun parseDue(text: String): Pair<String?, Boolean>? = DueText.parse(text, zone)

    fun refresh() = requestSync()

    companion object {
        const val SERVER_INBOX = "Inbox"

        /** Priority first (1 → 3 → none), then due date, then newest. */
        val ORDER: Comparator<TaskEntity> = compareBy<TaskEntity> { it.priority ?: 4 }
            .thenBy { it.dueAt ?: "9999" }
            .thenByDescending { it.createdAt }
    }
}
