package com.taskly.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.taskly.android.core.designsystem.TasklyTheme
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.feature.tasks.TaskListScreen
import com.taskly.android.feature.tasks.TaskListViewModel

/**
 * Main Activity — entry point for the Compose-based UI.
 *
 * Hilt readiness: when Hilt codegen supports Kotlin 2.4+,
 * add @AndroidEntryPoint, inject dependencies via @Inject fields,
 * and remove the manual AppContainer wiring.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TasklyApp

        // Start periodic background sync
        SyncEngine.initOnAppStart(applicationContext)

        setContent {
            TasklyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TaskListScreen(
                        viewModel = TaskListViewModel(
                            database = app.appContainer.database,
                            authRepository = app.appContainer.authRepository,
                            syncEngine = app.appContainer.syncEngine
                        )
                    )
                }
            }
        }
    }
}