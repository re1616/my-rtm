package com.taskly.server.tasks

import com.taskly.server.auth.AuthService
import io.ktor.http.*
import io.ktor.server.application.*

fun requireUserId(call: ApplicationCall): String {
    val authHeader = call.request.headers[HttpHeaders.Authorization]
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        throw IllegalArgumentException("Missing or invalid Authorization header")
    }
    val token = authHeader.removePrefix("Bearer ")
    return AuthService.validateAccessToken(token)
}