package com.taskly.android

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.taskly.android.core.network.TasklyApiClient
import com.taskly.android.core.sync.RealtimeClient
import com.taskly.android.core.sync.SessionTokens
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.core.sync.SyncSession
import com.taskly.android.core.sync.reminders.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hilt is applied without its Gradle plugin, so the class names its base explicitly and extends
 * the generated [Hilt_TasklyApp].
 *
 * In the foreground the app keeps a WebSocket open and pulls on every `sync_update`; in the
 * background FCM data messages do the same (ARCH §6). Alarms are rebuilt when the app starts.
 */
@HiltAndroidApp(Application::class)
class TasklyApp : Hilt_TasklyApp() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var realtime: RealtimeClient? = null

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.ensureChannel(this)
        SyncEngine.schedulePeriodic(this)
        // Flush anything queued while the app was closed as soon as the device is online
        SyncEngine.requestSync(this)
        appScope.launch { ReminderScheduler.reschedule(this@TasklyApp) }
        initFirebase()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startRealtime()
            override fun onStop(owner: LifecycleOwner) = stopRealtime()
        })
    }

    private fun startRealtime() {
        val session = SyncSession(this)
        if (session.userId == null) return
        val api = TasklyApiClient.create(session.baseUrl)
        realtime?.stop()
        realtime = RealtimeClient(session.baseUrl, SessionTokens(session, api), onUpdate = { SyncEngine.requestSync(this) })
            .also { it.start(appScope) }
        SyncEngine.requestSync(this)
        fcmToken?.let { registerPushToken(it) }
    }

    private fun stopRealtime() {
        realtime?.stop()
        realtime = null
    }

    /** Signed in from the UI: open the socket right away (the process is already started). */
    fun onSignedIn() {
        startRealtime()
        fcmToken?.let { registerPushToken(it) }
    }

    @Volatile
    private var fcmToken: String? = null

    /**
     * FCM is configured from build properties (no google-services.json in the repository):
     * without them the app works with periodic sync, the socket and local alarms only.
     */
    private fun initFirebase() {
        if (BuildConfig.FCM_PROJECT_ID.isBlank() || BuildConfig.FCM_APP_ID.isBlank()) return
        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setApplicationId(BuildConfig.FCM_APP_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .build(),
                )
            }
            FirebaseMessaging.getInstance().token.addOnSuccessListener { registerPushToken(it) }
        }.onFailure { Log.w(TAG, "FCM is not available", it) }
    }

    /** Sends the FCM token to the server with whether this device can use exact alarms (AND-N2). */
    fun registerPushToken(token: String) {
        fcmToken = token
        val session = SyncSession(this)
        if (session.userId == null) return
        appScope.launch {
            val api = TasklyApiClient.create(session.baseUrl)
            val tokens = SessionTokens(session, api)
            val exact = ReminderScheduler.canScheduleExact(this@TasklyApp)
            runCatching { api.registerDevice(token, tokens.accessToken() ?: return@launch, exact) }
                .onFailure {
                    runCatching { tokens.refresh()?.let { access -> api.registerDevice(token, access, exact) } }
                }
        }
    }

    private companion object {
        const val TAG = "TasklyApp"
    }
}
