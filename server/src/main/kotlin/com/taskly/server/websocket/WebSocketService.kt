package com.taskly.server.websocket

import com.taskly.server.auth.AuthService
import com.taskly.server.redis.RedisService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPubSub
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

                        userId = uid
                        scopes = (authData.scopes?.toMutableSet() ?: mutableSetOf("user:$uid"))

                        // Register session
                        userSessions.getOrPut(uid) { ConcurrentHashMap.newKeySet() }.add(session)
                        sessionScopes[session] = scopes

                        // Start Redis listener for this user in a background coroutine
                        CoroutineScope(Dispatchers.IO).launch {
                            listenForUpdates(uid, scopes, session)
                        }

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
                        scopes.add(subData.scope)
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
            // Cleanup on disconnect
            if (userId != null) {
                userSessions[userId]?.remove(session)
                if (userSessions[userId]?.isEmpty() == true) {
                    userSessions.remove(userId)
                }
            }
            sessionScopes.remove(session)
        }
    }

    /**
     * Listen for Redis pub/sub messages for the user's scopes in a background thread.
     * Jedis.subscribe is blocking, so this runs on IO dispatcher.
     */
    private suspend fun listenForUpdates(
        userId: String,
        scopes: MutableSet<String>,
        session: DefaultWebSocketServerSession
    ) {
        val channels = scopes.map { "sync:$userId:$it" }.toTypedArray()

        // Run the blocking Redis subscribe on a separate thread
        withContext(Dispatchers.IO) {
            val pubSub = object : JedisPubSub() {
                override fun onMessage(channel: String, message: String) {
                    try {
                        CoroutineScope(Dispatchers.Default).launch {
                            session.send(Frame.Text(json.encodeToString(WsMessage("sync_update", message))))
                        }
                    } catch (e: Exception) {
                        // Session may be closed
                    }
                }
            }

            // This blocks until unsubscribe
            RedisService.subscribe(pubSub, *channels)
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