package com.taskly.android.core.sync

import android.content.Context

/**
 * Signed-in session and sync cursor, persisted so that background sync (WorkManager)
 * and the UI see the same state across restarts.
 */
class SyncSession(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val accessToken: String? get() = prefs.getString(KEY_TOKEN, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)
    val userId: String? get() = prefs.getString(KEY_USER_ID, null)
    val baseUrl: String get() = prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL

    var cursor: Long
        get() = prefs.getLong(KEY_CURSOR, 0L)
        set(value) = prefs.edit().putLong(KEY_CURSOR, value).apply()

    fun signIn(accessToken: String, refreshToken: String, userId: String) {
        val editor = prefs.edit()
        // A different account starts from an empty cursor
        if (prefs.getString(KEY_USER_ID, null) != userId) editor.putLong(KEY_CURSOR, 0L)
        editor.putString(KEY_TOKEN, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun signOut() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_REFRESH)
            .remove(KEY_USER_ID)
            .remove(KEY_CURSOR)
            .apply()
    }

    companion object {
        /** Emulator alias for the host machine; overridable via [KEY_BASE_URL]. */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"
        private const val PREFS = "taskly_auth"
        private const val KEY_TOKEN = "auth_access_token"
        private const val KEY_REFRESH = "auth_refresh_token"
        private const val KEY_USER_ID = "auth_user_id"
        private const val KEY_CURSOR = "sync_cursor"
        private const val KEY_BASE_URL = "api_base_url"
    }
}
