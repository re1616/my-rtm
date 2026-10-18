package com.taskly.server.auth

import com.taskly.server.dto.*
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.ratelimit.RateLimitExceededException
import com.taskly.server.tasks.requireUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*

fun Route.authRoutes() {
    post("/v1/auth/register") {
        // SEC-6: per-IP rate limit on register
        if (!RateLimiter.check("auth:register")) {
            throw RateLimitExceededException("Too many registration attempts. Try again later.")
        }

        val request = call.receive<RegisterRequest>()
        try {
            val response = AuthService.register(request)
            call.respond(HttpStatusCode.Created, response)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(error = e.message ?: "Registration failed"))
        }
    }

    post("/v1/auth/login") {
        // SEC-6: per-IP throttle
        if (!RateLimiter.check("auth:login")) {
            throw RateLimitExceededException("Too many requests. Try again later.")
        }

        val request = call.receive<LoginRequest>()

        // SEC-6: per-account rate limit (5 failed attempts)
        val accountKey = "login:${request.email.lowercase().trim()}"
        if (RateLimiter.checkAccountLimit(accountKey)) {
            throw RateLimitExceededException("Too many failed login attempts. Try again later.")
        }

        try {
            val response = AuthService.login(request)
            // Clear failed attempts on success
            RateLimiter.clearAccountLimit(accountKey)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: IllegalArgumentException) {
            // Record failed attempt
            RateLimiter.recordFailedAttempt(accountKey)
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = e.message ?: "Login failed"))
        }
    }

    post("/v1/auth/refresh") {
        // SEC-6: per-IP throttle
        if (!RateLimiter.check("auth:refresh")) {
            throw RateLimitExceededException("Too many requests. Try again later.")
        }

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