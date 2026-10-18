package com.taskly.server.auth

import com.taskly.server.Config
import com.taskly.server.dto.*
import com.taskly.server.errors.NotFoundException
import com.taskly.server.errors.UnauthorizedException
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

        // SEC-6: per-account lock after repeated failed attempts (per-IP limit: RateLimitPlugin)
        if (RateLimiter.isAccountLocked(request.email)) {
            throw RateLimitExceededException("Too many failed login attempts. Try again later.")
        }

        try {
            val response = AuthService.login(request)
            RateLimiter.clearFailedLogins(request.email)
            call.respond(HttpStatusCode.OK, response)
        } catch (e: IllegalArgumentException) {
            RateLimiter.recordFailedLogin(request.email)
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = e.message ?: "Login failed"))
        } catch (e: UnauthorizedException) {
            // A wrong authenticator code counts as a failed attempt; "code required" does not
            if (e.code == "totp_invalid") RateLimiter.recordFailedLogin(request.email)
            throw e
        }
    }

    // ── Password reset ──

    post("/v1/auth/password/forgot") {
        val request = call.receive<ForgotPasswordRequest>()
        AuthService.forgotPassword(request.email, Config.appUrl)
        // Same answer whether or not the email is registered
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "sent_if_registered"))
    }

    post("/v1/auth/password/reset") {
        val request = call.receive<ResetPasswordRequest>()
        AuthService.resetPassword(request.token, request.newPassword)
        call.respond(HttpStatusCode.OK, mapOf("status" to "password_changed"))
    }

    // ── Two-factor authentication ──

    get("/v1/auth/account") {
        call.respond(HttpStatusCode.OK, AuthService.account(requireUserId(call)))
    }

    post("/v1/auth/2fa/setup") {
        call.respond(HttpStatusCode.OK, AuthService.setupTotp(requireUserId(call)))
    }

    post("/v1/auth/2fa/enable") {
        val userId = requireUserId(call)
        AuthService.enableTotp(userId, call.receive<TotpCodeRequest>().code)
        call.respond(HttpStatusCode.OK, mapOf("status" to "enabled"))
    }

    post("/v1/auth/2fa/disable") {
        val userId = requireUserId(call)
        AuthService.disableTotp(userId, call.receive<TotpCodeRequest>().code)
        call.respond(HttpStatusCode.OK, mapOf("status" to "disabled"))
    }

    // ── Google sign-in ──

    post("/v1/auth/google") {
        val verifier = GoogleSignIn.verifier
            ?: throw NotFoundException("Google sign-in is not configured on this server")
        val request = call.receive<GoogleLoginRequest>()
        val identity = verifier.verify(request.idToken)
        call.respond(HttpStatusCode.OK, AuthService.googleLogin(identity, request.totpCode))
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