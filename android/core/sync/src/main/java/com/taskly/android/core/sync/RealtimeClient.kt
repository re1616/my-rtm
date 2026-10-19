package com.taskly.android.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.random.Random

/**
 * Live updates while the app is visible (AND-S4): the server's WebSocket says "there are
 * changes" and the app pulls them. In the background FCM does the same job (no open socket).
 * Reconnects with exponential backoff and jitter (1 s → 5 min); a rejected token is refreshed.
 */
class RealtimeClient(
    baseUrl: String,
    private val tokens: TokenProvider,
    private val onUpdate: suspend () -> Unit,
    private val http: HttpClient = HttpClient(CIO) { install(WebSockets) },
) {
    private val url = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/v1/ws"
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    /** Connected and authenticated right now (for tests and the status indicator). */
    @Volatile
    var connected: Boolean = false
        private set

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                val token = tokens.accessToken()
                if (token == null) {
                    delay(5_000)
                    continue
                }
                var rejected = false
                try {
                    http.webSocket(url) {
                        val auth = buildJsonObject {
                            put("type", "auth")
                            put("payload", buildJsonObject { put("token", token) }.toString())
                        }
                        send(Frame.Text(auth.toString()))
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                            when (msg["type"]?.jsonPrimitive?.content) {
                                "auth_ok" -> {
                                    connected = true
                                    attempt = 0
                                    onUpdate() // catch up on what happened while disconnected
                                }
                                "sync_update" -> onUpdate()
                                "error" -> {
                                    rejected = true
                                    break
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // network gone or server restarted: reconnect below
                } finally {
                    connected = false
                }
                if (rejected) tokens.refresh() ?: return@launch // signed out
                attempt++
                delay(backoff(attempt))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        connected = false
    }

    companion object {
        /** Equal jitter: half fixed, half random; 1 s doubling up to 5 min. */
        fun backoff(attempt: Int, random: Random = Random.Default): Long {
            val cap = min(300_000L, 1_000L shl min(attempt - 1, 18).coerceAtLeast(0))
            return cap / 2 + random.nextLong(cap / 2 + 1)
        }

        @Suppress("unused")
        private fun JsonPrimitive.text() = content
    }
}
