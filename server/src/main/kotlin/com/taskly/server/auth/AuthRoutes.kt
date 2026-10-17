package com.taskly.server.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import com.taskly.server.dto.*
import com.taskly.server.tasks.requireUserId

fun Route.authRoutes() {
    post("/v1/auth/register") {
        val request = call.receive<RegisterRequest>()
        try {
            val response = AuthService.register(request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(error = e.message ?: "Registration failed"))
        }
    }

    post("/v1/auth/login") {
        val request = call.receive<LoginRequest>()
        try {
            val response = AuthService.login(request)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = e.message ?: "Login failed"))
        }
    }

    post("/v1/auth/refresh") {
        val request = call.receive<RefreshTokenRequest>()
        try {
            val response = AuthService.refreshToken(request.refreshToken)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = e.message ?: "Token refresh failed"))
        }
    }

    post("/v1/auth/logout") {
        val userId = try {
            requireUserId(call)
        } catch (_: Exception) {
            // Optional auth for logout
            null
        }
        if (userId != null) {
            AuthService.logout(userId)
        }
        call.respond(HttpStatusCode.OK, mapOf("status" to "logged_out"))
    }
}