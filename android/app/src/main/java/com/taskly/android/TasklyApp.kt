package com.taskly.android

import android.app.Application
import com.taskly.android.core.sync.SyncEngine
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt is applied without its Gradle plugin, so the class names its base explicitly and extends
 * the generated [Hilt_TasklyApp].
 */
@HiltAndroidApp(Application::class)
class TasklyApp : Hilt_TasklyApp() {
    override fun onCreate() {
        super.onCreate()
        SyncEngine.schedulePeriodic(this)
        // Flush anything queued while the app was closed as soon as the device is online
        SyncEngine.requestSync(this)
    }
}
