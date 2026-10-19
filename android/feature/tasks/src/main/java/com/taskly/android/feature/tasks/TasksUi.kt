package com.taskly.android.feature.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskly.android.core.database.ConflictEntity
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.sync.SyncStatus
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Things only the activity can do (runtime permissions, system settings screens). */
data class SystemActions(
    val notificationsGranted: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val requestNotifications: () -> Unit = {},
    val openExactAlarmSettings: () -> Unit = {},
)

/** Width from which the list and the task card are shown side by side (AND-UI: expanded). */
const val TWO_PANE_MIN_WIDTH_DP = 840

/** Root of the app: sign-in, or the task screens. */
@Composable
fun TasklyRoot(vm: MainViewModel, system: SystemActions = SystemActions()) {
    val userId by vm.userId.collectAsState()
    if (userId == null) AuthScreen(vm) else MainScreen(vm, system)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, system: SystemActions = SystemActions()) {
    val screen by vm.screen.collectAsState()
    val openTaskId by vm.openTaskId.collectAsState()
    val twoPane = LocalConfiguration.current.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Undo snackbar for every reversible change (SPEC FR-6)
    LaunchedEffect(vm) {
        vm.undo.collect { event ->
            launch {
                val result = snackbar.showSnackbar(context.getString(event.message), context.getString(R.string.undo), withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed) event.undo()
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen || openTaskId != null || screen != MainViewModel.Screen.TASKS) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            openTaskId != null -> vm.openTaskId.value = null
            else -> vm.screen.value = MainViewModel.Screen.TASKS
        }
    }

    val drawer: @Composable () -> Unit = {
        DrawerContent(vm) { scope.launch { drawerState.close() } }
    }

    val content: @Composable () -> Unit = {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    screen == MainViewModel.Screen.SETTINGS -> SettingsScreen(vm, system)
                    openTaskId != null && !twoPane -> TaskDetailScreen(vm)
                    twoPane -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            TaskListPane(vm, showMenu = false) { scope.launch { drawerState.open() } }
                        }
                        if (openTaskId != null) {
                            Box(Modifier.weight(1f).fillMaxHeight().testTag("detail_pane")) { TaskDetailScreen(vm) }
                        }
                    }
                    else -> TaskListPane(vm, showMenu = true) { scope.launch { drawerState.open() } }
                }
            }
        }
        ConflictDialog(vm)
    }

    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            PermanentDrawerSheet(Modifier.width(280.dp)) { drawer() }
            content()
        }
    } else {
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = { ModalDrawerSheet { drawer() } }) { content() }
    }
}

@Composable
private fun DrawerContent(vm: MainViewModel, close: () -> Unit) {
    val view by vm.view.collectAsState()
    val screen by vm.screen.collectAsState()
    val lists by vm.userLists.collectAsState()
    val smart by vm.smartLists.collectAsState()
    val tags by vm.tagNames.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.app_name_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
        fun go(v: TaskView) { vm.select(v); close() }
        val system = listOf(
            TaskView.System.INBOX to R.string.view_inbox,
            TaskView.System.TODAY to R.string.view_today,
            TaskView.System.TOMORROW to R.string.view_tomorrow,
            TaskView.System.WEEK to R.string.view_week,
            TaskView.System.OVERDUE to R.string.view_overdue,
            TaskView.System.ALL to R.string.view_all,
        )
        for ((kind, label) in system) {
            val v = TaskView.Sys(kind)
            NavigationDrawerItem(
                label = { Text(stringResource(label)) },
                selected = screen == MainViewModel.Screen.TASKS && view == v,
                onClick = { go(v) },
                modifier = Modifier.testTag("nav_${kind.name.lowercase()}"),
            )
        }
        if (lists.isNotEmpty()) {
            DrawerHeader(R.string.drawer_lists)
            for (l in lists) {
                val v = TaskView.OfList(l.id)
                NavigationDrawerItem(label = { Text(l.name) }, selected = view == v, onClick = { go(v) })
            }
        }
        if (smart.isNotEmpty()) {
            DrawerHeader(R.string.drawer_smart)
            for (l in smart) {
                val v = TaskView.Smart(l.id)
                NavigationDrawerItem(label = { Text(l.name) }, selected = view == v, onClick = { go(v) })
            }
        }
        if (tags.isNotEmpty()) {
            DrawerHeader(R.string.drawer_tags)
            for (t in tags) {
                val v = TaskView.Tag(t)
                NavigationDrawerItem(label = { Text("#$t") }, selected = view == v, onClick = { go(v) })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.drawer_settings)) },
            selected = screen == MainViewModel.Screen.SETTINGS,
            onClick = { vm.screen.value = MainViewModel.Screen.SETTINGS; vm.loadSettings(); close() },
            modifier = Modifier.testTag("nav_settings"),
        )
        NavigationDrawerItem(label = { Text(stringResource(R.string.drawer_sign_out)) }, selected = false, onClick = { close(); vm.logout() })
    }
}

@Composable
private fun DrawerHeader(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
fun viewTitle(vm: MainViewModel, v: TaskView): String {
    val lists by vm.lists.collectAsState()
    return when (v) {
        is TaskView.Sys -> stringResource(
            when (v.kind) {
                TaskView.System.INBOX -> R.string.view_inbox
                TaskView.System.TODAY -> R.string.view_today
                TaskView.System.TOMORROW -> R.string.view_tomorrow
                TaskView.System.WEEK -> R.string.view_week
                TaskView.System.OVERDUE -> R.string.view_overdue
                TaskView.System.ALL -> R.string.view_all
                TaskView.System.COMPLETED -> R.string.view_completed
            },
        )
        is TaskView.OfList -> lists.firstOrNull { it.id == v.listId }?.name.orEmpty()
        is TaskView.Smart -> lists.firstOrNull { it.id == v.listId }?.name.orEmpty()
        is TaskView.Tag -> "#${v.name}"
        is TaskView.Search -> stringResource(R.string.view_search, v.text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListPane(vm: MainViewModel, showMenu: Boolean, openDrawer: () -> Unit) {
    val view by vm.view.collectAsState()
    val state by vm.visible.collectAsState()
    val completedTab by vm.showCompleted.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                if (showMenu) IconButton(onClick = openDrawer, modifier = Modifier.testTag("open_drawer")) {
                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                }
            },
            title = {
                if (searching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it; vm.view.value = TaskView.Search(it) },
                        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("search_field"),
                    )
                } else {
                    Text(viewTitle(vm, view), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            actions = {
                if (searching) {
                    IconButton(onClick = { searching = false; query = ""; vm.select(TaskView.Sys(TaskView.System.INBOX)) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                } else {
                    IconButton(onClick = { searching = true }, modifier = Modifier.testTag("open_search")) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                    }
                }
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_sync)) }
            },
        )
        SyncBanner(vm)
        val tabbed = view is TaskView.OfList || view is TaskView.Tag || view == TaskView.Sys(TaskView.System.INBOX) || view == TaskView.Sys(TaskView.System.ALL)
        if (tabbed) {
            TabRow(selectedTabIndex = if (completedTab) 1 else 0) {
                Tab(selected = !completedTab, onClick = { vm.showCompleted.value = false }, text = { Text(stringResource(R.string.tab_open)) })
                Tab(selected = completedTab, onClick = { vm.showCompleted.value = true }, text = { Text(stringResource(R.string.tab_done)) }, modifier = Modifier.testTag("tab_completed"))
            }
        }
        state.error?.let {
            Text(stringResource(R.string.query_error, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).testTag("query_error"))
        }
        Box(Modifier.weight(1f)) {
            if (state.tasks.isEmpty() && state.error == null) {
                Text(stringResource(R.string.empty_list), modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize().testTag("task_list")) {
                items(state.tasks, key = { it.id }) { task ->
                    SwipeableTaskRow(vm, task, state.tags[task.id].orEmpty())
                }
            }
        }
        if (view !is TaskView.Search && view != TaskView.Sys(TaskView.System.COMPLETED) && !completedTab) SmartAddBar(vm)
    }
}

@Composable
fun SyncBanner(vm: MainViewModel) {
    val status by vm.status.collectAsState()
    val pending by vm.pendingChanges.collectAsState()
    when (val s = status) {
        is SyncStatus.Snapshot -> Column(Modifier.fillMaxWidth().testTag("banner_snapshot")) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.banner_snapshot, s.loaded), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        is SyncStatus.Offline -> Banner(
            if (pending > 0) stringResource(R.string.banner_offline, pending) else stringResource(R.string.banner_offline_none),
            "banner_offline",
        )
        is SyncStatus.Error -> Banner(stringResource(R.string.banner_error, s.message), "banner_error")
        SyncStatus.Syncing -> LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        SyncStatus.Idle -> Unit
    }
}

@Composable
private fun Banner(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 6.dp).testTag(tag),
    )
}

fun priorityColor(p: Int?): Color = when (p) {
    1 -> Color(0xFFE53935)
    2 -> Color(0xFF1E88E5)
    3 -> Color(0xFF64B5F6)
    else -> Color.Transparent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskRow(vm: MainViewModel, task: TaskEntity, tags: List<String>) {
    // Swipe right → complete, swipe left → postpone (AND-UI); the row then leaves the view
    val current by rememberUpdatedState(task)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { vm.toggleComplete(current); true }
                SwipeToDismissBoxValue.EndToStart -> { vm.postpone(current); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(if (toEnd) Color(0xFF43A047) else Color(0xFFFFA000)).padding(horizontal = 20.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(stringResource(if (toEnd) R.string.swipe_complete else R.string.swipe_postpone), color = Color.White)
            }
        },
        modifier = Modifier.testTag("task_${task.id}"),
    ) {
        TaskRow(vm, task, tags)
    }
}

@Composable
fun TaskRow(vm: MainViewModel, task: TaskEntity, tags: List<String>) {
    val zone = remember { ZoneId.systemDefault() }
    val labels = DueText.Labels(stringResource(R.string.view_today), stringResource(R.string.view_tomorrow), stringResource(R.string.view_yesterday))
    val due = DueText.format(task.dueAt, task.dueHasTime, zone, labels)
    val overdue = task.completedAt == null && DueText.isOverdue(task.dueAt, task.dueHasTime, zone)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { vm.openTaskId.value = task.id }.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(48.dp).background(priorityColor(task.priority)))
        Checkbox(
            checked = task.completedAt != null,
            onCheckedChange = { vm.toggleComplete(task) },
            modifier = Modifier.semantics { contentDescription = task.name },
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                task.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.completedAt != null) TextDecoration.LineThrough else null,
            )
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (due != null) {
            Text(due, style = MaterialTheme.typography.bodySmall, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

@Composable
fun SmartAddBar(vm: MainViewModel, parentId: String? = null, hint: Int = R.string.smart_add_hint, tag: String = "smart_add") {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        val value = text.trim()
        if (value.isNotEmpty()) {
            vm.addFromSmartAdd(value, parentId)
            text = ""
        }
    }
    Row(Modifier.fillMaxWidth().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).testTag(tag),
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = submit, modifier = Modifier.testTag("${tag}_send")) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.smart_add_send))
        }
    }
}

/** Concurrent edits of the name / a note (SYNC-3): the user picks which value stays. */
@Composable
fun ConflictDialog(vm: MainViewModel) {
    val conflicts by vm.conflicts.collectAsState()
    val c: ConflictEntity = conflicts.firstOrNull() ?: return
    val field = stringResource(if (c.entityType == "note") R.string.field_content else R.string.field_name)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.conflict_text, field))
                Text(stringResource(R.string.conflict_mine, c.localValue.orEmpty()), modifier = Modifier.testTag("conflict_mine"))
                Text(stringResource(R.string.conflict_server, c.serverValue.orEmpty()), modifier = Modifier.testTag("conflict_server"))
            }
        },
        confirmButton = { TextButton(onClick = { vm.resolve(c, keepMine = true) }, modifier = Modifier.testTag("keep_mine")) { Text(stringResource(R.string.conflict_keep_mine)) } },
        dismissButton = { TextButton(onClick = { vm.resolve(c, keepMine = false) }, modifier = Modifier.testTag("keep_server")) { Text(stringResource(R.string.conflict_keep_server)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = { actions() },
    )
}
