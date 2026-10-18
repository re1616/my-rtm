package com.taskly.server.plugins

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import com.taskly.server.auth.authRoutes
import com.taskly.server.tasks.taskRoutes
import com.taskly.server.lists.listRoutes
import com.taskly.server.tags.tagRoutes
import com.taskly.server.sync.syncRoutes
import com.taskly.server.websocket.WebSocketService
import com.taskly.server.errors.*
import com.taskly.server.ratelimit.RateLimitPlugin
import io.ktor.server.plugins.BadRequestException

private val logger = LoggerFactory.getLogger("Setup")

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val status = when (cause) {
                is UnauthorizedException -> HttpStatusCode.Unauthorized
                is ForbiddenException -> HttpStatusCode.Forbidden
                is NotFoundException -> HttpStatusCode.NotFound
                is ConflictException -> HttpStatusCode.Conflict
                is TooManyRequestsException -> HttpStatusCode.TooManyRequests
                is BadRequestException -> HttpStatusCode.BadRequest
                is IllegalArgumentException -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.InternalServerError
            }
            if (cause is TooManyRequestsException) {
                call.response.headers.append(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            }
            // Never leak internal exception messages for 5xx
            val message = if (status == HttpStatusCode.InternalServerError) "Internal server error"
                else cause.message ?: status.description
            call.respond(status, mapOf("error" to message))
            if (status == HttpStatusCode.InternalServerError) {
                logger.error("Unhandled exception", cause)
            }
        }
    }
}

fun Application.configureRouting() {
    install(RateLimitPlugin)
    install(WebSockets)
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "taskly"))
        }

        authRoutes()
        taskRoutes()
        listRoutes()
        tagRoutes()
        syncRoutes()

        webSocket("/v1/ws") {
            WebSocketService.handleSession(this)
        }
    }
}