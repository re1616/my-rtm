package com.taskly.server.tasks

import com.taskly.server.auth.AuthService
import com.taskly.server.errors.UnauthorizedException
import io.ktor.http.*
import io.ktor.server.application.*

/** Returns the authenticated user id or throws [UnauthorizedException] (→ 401). */
fun requireUserId(call: ApplicationCall): String {
    val authHeader = call.request.headers[HttpHeaders.Authorization]
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        throw UnauthorizedException("Missing or invalid Authorization header")
    }
    val token = authHeader.removePrefix("Bearer ")
    return try {
        AuthService.validateAccessToken(token)
    } catch (e: IllegalArgumentException) {
        throw UnauthorizedException(e.message ?: "Invalid or expired access token")
    }
}
