package com.taskly.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.Application
import com.taskly.server.plugins.*
import com.taskly.server.db.Database
import com.taskly.server.redis.RedisService
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.auth.AuthService

fun main() {
    // Initialize infrastructure
    Database.init(Config.database)
    RedisService.init(Config.redis)
    RateLimiter.init(Config.rateLimit)
    AuthService.init(Config.auth)

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureSerialization()
        configureStatusPages()
        configureRouting()
    }.start(wait = true)
}

// Test helper
fun Application.moduleForTest() {
    configureSerialization()
    configureStatusPages()
    configureRouting()
}