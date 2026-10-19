package com.taskly.server.websocket

import com.taskly.server.auth.AuthService
import com.taskly.server.redis.RedisFanout
import com.taskly.server.sync.ChangeLog
import java.util.UUID
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket service for real-time updates.
 * Uses Redis pub/sub to fanout changes across server instances.
 */
object WebSocketService {
    private val log = LoggerFactory.getLogger("WebSocketService")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Active sessions per user: userId -> set of WebSocket sessions
    private val userSessions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()
    private val sessionScopes = ConcurrentHashMap<DefaultWebSocketServerSession, MutableSet<String>>()

    /**
     * Install WebSocket plugin and configure the endpoint on the application.
     */
    fun setupWebSocket(application: Application) {
        application.install(WebSockets)
        application.routing {
            webSocket("/v1/ws") {
                handleSession(this)
            }
        }
    }

    /**
     * Handle a WebSocket session.
     */
    suspend fun handleSession(session: DefaultWebSocketServerSession) {
        var userId: String? = null
        var scopes = mutableSetOf<String>()
        val subscriptions = mutableListOf<AutoCloseable>()
        fun listen(channel: String) {
            subscriptions += RedisFanout.listen(channel) { message ->
                session.launch {
                    try {
                        session.send(Frame.Text(json.encodeToString(WsMessage("sync_update", message))))
                    } catch (_: Exception) {
                        // session closed
                    }
                }
            }
        }

        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue

                val text = frame.readText()
                val message = try {
                    json.decodeFromString<WsMessage>(text)
                } catch (e: Exception) {
                    session.send(Frame.Text(json.encodeToString(WsMessage("error", "Invalid message format"))))
                    continue
                }

                when (message.type) {
                    "auth" -> {
                        val authData = try {
                            json.decodeFromString<WsAuthPayload>(message.payload)
                        } catch (e: Exception) {
                            session.send(Frame.Text(json.encodeToString(WsMessage("error", "Invalid auth payload"))))
                            continue
                        }

                        val uid = try {
                            AuthService.validateAccessToken(authData.token)
                        } catch (e: Exception) {
                            session.send(Frame.Text(json.encodeToString(WsMessage("error", "Invalid token"))))
                            continue
                        }

                        if (userId != null) {
                            session.send(Frame.Text(json.encodeToString(WsMessage("error", "Already authenticated"))))
                            continue
                        }
                        // Scopes are validated against what the user may read; never trusted as-is
                        val allowed = ChangeLog.allowedScopes(UUID.fromString(uid))
                        val requested = authData.scopes?.toSet() ?: allowed
                        val denied = requested - allowed
                        if (denied.isNotEmpty()) {
                            session.send(Frame.Text(json.encodeToString(WsMessage("error", "Scopes not allowed: ${denied.joinToString()}"))))
                            continue
                        }
                        userId = uid
                        scopes = requested.toMutableSet()

                        // Register session
                        userSessions.getOrPut(uid) { ConcurrentHashMap.newKeySet() }.add(session)
                        sessionScopes[session] = scopes

                        // One shared Redis subscription per server; this socket listens to its channels.
                        // Registered before auth_ok, so no change after auth_ok is missed.
                        withContext(Dispatchers.IO) { RedisFanout.ensureStarted() }
                        for (scope in scopes) listen(ChangeLog.channel(UUID.fromString(uid), scope))

                        session.send(Frame.Text(json.encodeToString(WsMessage("auth_ok", """{"userId":"$uid"}"""))))
                    }

                    "subscribe" -> {
                        if (userId == null) {
                            session.send(Frame.Text(json.encodeToString(WsMessage("error", "Not authenticated"))))
                            continue
                        }
                        val subData = try {
                            json.decodeFromString<WsSubscribePayload>(message.payload)
                        } catch (e: Exception) {
                            session.send(Frame.Text(json.encodeToString(WsMessage("error", "Invalid subscribe payload"))))
                            continue
                        }
                        if (subData.scope !in ChangeLog.allowedScopes(UUID.fromString(userId))) {
                            session.send(Frame.Text(json.encodeToString(WsMessage("error", "Scope not allowed: ${subData.scope}"))))
                            continue
                        }
                        if (subData.scope !in scopes) {
                            scopes.add(subData.scope)
                            listen(ChangeLog.channel(UUID.fromString(userId), subData.scope))
                        }
                        sessionScopes[session] = scopes
                        session.send(Frame.Text(json.encodeToString(WsMessage("subscribed", """{"scope":"${subData.scope}"}"""))))
                    }

                    "ping" -> {
                        session.send(Frame.Text(json.encodeToString(WsMessage("pong", "{}"))))
                    }

                    else -> {
                        session.send(Frame.Text(json.encodeToString(WsMessage("error", "Unknown message type: ${message.type}"))))
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("WebSocket session error: ${e.message}")
        } finally {
            // Cleanup on disconnect: stop listening (the shared subscription stays)
            subscriptions.forEach { runCatching { it.close() } }
            if (userId != null) {
                userSessions[userId]?.remove(session)
                if (userSessions[userId]?.isEmpty() == true) {
                    userSessions.remove(userId)
                }
            }
            sessionScopes.remove(session)
        }
    }
}

// ============================================================
// WebSocket message DTOs
// ============================================================

@Serializable
data class WsMessage(
    val type: String,
    val payload: String
)

@Serializable
data class WsAuthPayload(
    val token: String,
    val scopes: List<String>? = null
)

@Serializable
data class WsSubscribePayload(
    val scope: String
)