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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel
) {
    val coroutineScope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(viewModel.auth().isLoggedIn()) }
    var tasks by remember { mutableStateOf<List<TaskEntity>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Collect tasks from the DB when logged in
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            val ownerId = viewModel.getUserId() ?: ""
            viewModel.taskDao().getAll(ownerId).collect { taskList ->
                tasks = taskList
            }
        }
    }

    if (!isLoggedIn) {
        LoginForm(
            authRepository = viewModel.auth(),
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
                                    val userId = viewModel.getUserId() ?: return@launch
                                    viewModel.repository().toggleComplete(task, userId)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Refresh on demand — process queue + pull deltas
        LaunchedEffect(isRefreshing) {
            if (isRefreshing) {
                viewModel.syncNow()
                isRefreshing = false
            }
        }

        if (showCreateDialog) {
            CreateTaskDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name ->
                    coroutineScope.launch {
                        val ownerId = viewModel.getUserId() ?: return@launch
                        viewModel.repository().createTask(ownerId, name.trim())
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
                Text(
                    text = "A new account will be created with this email.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            authRepository.register(email.trim(), password)
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
                enabled = email.isNotBlank() && password.isNotBlank()
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