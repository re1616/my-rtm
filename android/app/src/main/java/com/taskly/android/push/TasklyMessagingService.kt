package com.taskly.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.taskly.android.TasklyApp
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.core.sync.reminders.ReminderScheduler

/**
 * Data messages from the server (no notification payload, so they always reach the app):
 * `sync` — something changed on another device, pull now; `reminder` — the server's reminder
 * for devices that cannot use exact alarms.
 */
class TasklyMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        handle(this, message.data)
    }

    override fun onNewToken(token: String) {
        (application as? TasklyApp)?.registerPushToken(token)
    }

    companion object {
        fun handle(context: android.content.Context, data: Map<String, String>) {
            when (data["type"]) {
                "sync" -> SyncEngine.requestSync(context)
                "reminder" -> {
                    val taskId = data["taskId"] ?: return
                    ReminderScheduler.notify(context, taskId, data["title"].orEmpty())
                }
            }
        }
    }
}
