package com.taskly.android

import android.app.Application

/**
 * Taskly Android application.
 *
 * Hilt readiness: when Kotlin 2.4+ is compatible with Hilt codegen,
 * add @HiltAndroidApp annotation back and activate Hilt deps in
 * build.gradle.kts. The DI pattern below already supports the
 * Hilt module structure.
 */
class TasklyApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}