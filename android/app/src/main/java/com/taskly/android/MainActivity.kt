package com.taskly.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.designsystem.TasklyTheme
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.core.sync.TaskRepository
import com.taskly.android.core.sync.reminders.ReminderScheduler
import com.taskly.android.core.sync.reminders.ReminderSettingsStore
import com.taskly.android.feature.tasks.AuthRepository
import com.taskly.android.feature.tasks.MainViewModel
import com.taskly.android.feature.tasks.SystemActions
import com.taskly.android.feature.tasks.TasklyRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint(ComponentActivity::class)
class MainActivity : Hilt_MainActivity() {

    @Inject lateinit var db: TasklyDatabase
    @Inject lateinit var repo: TaskRepository
    @Inject lateinit var auth: AuthRepository

    private val app get() = application as TasklyApp

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    db, repo, auth, SyncEngine.processStatus,
                    requestSync = { SyncEngine.requestSync(applicationContext) },
                    onSettings = { settings ->
                        ReminderSettingsStore(applicationContext).settings = settings
                        app.appScope.launch { ReminderScheduler.reschedule(applicationContext) }
                    },
                )
            }
        }
    }

    private var notificationsGranted by mutableStateOf(true)
    private var exactAlarms by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openFromIntent(intent)
        lifecycleScope.launch {
            // Signed in from the UI: realtime + push registration for the new session
            var previous = viewModel.userId.value
            viewModel.userId.collect { id ->
                if (id != null && previous == null) app.onSignedIn()
                previous = id
            }
        }
        setContent {
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }
            TasklyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TasklyRoot(
                        viewModel,
                        SystemActions(
                            notificationsGranted = notificationsGranted,
                            exactAlarmsAllowed = exactAlarms,
                            requestNotifications = {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            openExactAlarmSettings = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                                }
                            },
                        ),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change in system settings while the app is in the background
        notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val exact = ReminderScheduler.canScheduleExact(this)
        if (exact != exactAlarms) {
            exactAlarms = exact
            app.appScope.launch { ReminderScheduler.reschedule(applicationContext) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    /** Tap on a reminder opens its task card (AND-N3). */
    private fun openFromIntent(intent: Intent?) {
        intent?.getStringExtra(ReminderScheduler.EXTRA_TASK_ID)?.let { viewModel.openTaskId.value = it }
    }
}
