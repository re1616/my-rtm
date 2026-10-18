package com.taskly.android.feature.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskly.android.core.database.TaskEntity
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.sync.SyncEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    database: TasklyDatabase,
    authRepository: AuthRepository,
    syncEngine: SyncEngine
) {
    val coroutineScope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(authRepository.isLoggedIn()) }
    var tasks by remember { mutableStateOf<List<TaskEntity>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Collect tasks from the DB when logged in
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            database.taskDao().getAll("").collect { taskList ->
                tasks = taskList
            }
        }
    }

    if (!isLoggedIn) {
        LoginForm(
            authRepository = authRepository,
            onLoginSuccess = { isLoggedIn = true }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Taskly") })
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add task")
                }
            }
        ) { padding ->
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator()
                    } else {
                        Text("No tasks yet", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onToggleComplete = { completed ->
                                coroutineScope.launch {
                                    val now = Clock.System.now().toString()
                                    val updatedTask = task.copy(
                                        completedAt = if (completed) now else null,
                                        updatedAt = now,
                                        version = task.version + 1
                                    )
                                    database.taskDao().upsert(updatedTask)
                                    val userId = authRepository.getUserId() ?: return@launch
                                    syncEngine.enqueueOperation(
                                        db = database,
                                        entityType = "task",
                                        entityId = task.id,
                                        operationType = "upsert",
                                        fields = mapOf(
                                            "completedAt" to updatedTask.completedAt,
                                            "updatedAt" to updatedTask.updatedAt,
                                            "version" to updatedTask.version
                                        ),
                                        userId = userId
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // Pull-to-refresh — manual trigger via a refresh button or gesture area
        // Simple approach: process queue + pull deltas on a button in the top bar area
        // For real pull-to-refresh, add SwipeRefresh or Modifier.pullRefresh; keeping it simple here
        LaunchedEffect(isRefreshing) {
            if (isRefreshing) {
                val token = authRepository.getAccessToken() ?: return@LaunchedEffect
                val userId = authRepository.getUserId() ?: return@LaunchedEffect
                syncEngine.processQueue(database, token)
                syncEngine.pullDeltas(database, userId, 0L, token)
                isRefreshing = false
            }
        }

        if (showCreateDialog) {
            CreateTaskDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name ->
                    coroutineScope.launch {
                        val now = Clock.System.now().toString()
                        val taskId = java.util.UUID.randomUUID().toString()
                        val ownerId = authRepository.getUserId() ?: ""
                        val task = TaskEntity(
                            id = taskId,
                            ownerId = ownerId,
                            listId = "default",
                            name = name,
                            version = 1,
                            createdAt = now,
                            updatedAt = now
                        )
                        database.taskDao().upsert(task)
                        val userId = authRepository.getUserId() ?: return@launch
                        syncEngine.enqueueOperation(
                            db = database,
                            entityType = "task",
                            entityId = taskId,
                            operationType = "upsert",
                            fields = mapOf(
                                "id" to taskId,
                                "name" to name,
                                "listId" to "default",
                                "createdAt" to now,
                                "updatedAt" to now
                            ),
                            userId = userId
                        )
                        showCreateDialog = false
                    }
                }
            )
        }
    }
}

@Composable
private fun LoginForm(
    authRepository: AuthRepository,
    onLoginSuccess: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Taskly",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            if (isRegister) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        error = null
                        val result = if (isRegister) {
                            authRepository.register(email.trim(), password, name.trim())
                        } else {
                            authRepository.login(email.trim(), password)
                        }
                        result.fold(
                            onSuccess = { onLoginSuccess() },
                            onFailure = { e -> error = e.message ?: "Authentication failed" }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = email.isNotBlank() && password.isNotBlank() && (!isRegister || name.isNotBlank())
            ) {
                Text(if (isRegister) "Register" else "Login")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = { isRegister = !isRegister; error = null }) {
                Text(
                    if (isRegister) "Already have an account? Login"
                    else "Don't have an account? Register"
                )
            }
        }
    }
}

@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var taskName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Task") },
        text = {
            OutlinedTextField(
                value = taskName,
                onValueChange = { taskName = it },
                label = { Text("Task name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(taskName.trim()) },
                enabled = taskName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    onToggleComplete: (Boolean) -> Unit
) {
    val dueText = task.dueAt
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = task.completedAt != null,
                onCheckedChange = { onToggleComplete(task.completedAt == null) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.completedAt != null)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                if (dueText != null) {
                    Text(
                        text = dueText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}