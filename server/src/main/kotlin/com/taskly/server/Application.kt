package com.taskly.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.Application
import com.taskly.server.plugins.*
import com.taskly.server.config.ConfigValidator
import com.taskly.server.config.validate
import com.taskly.server.db.Database
import com.taskly.server.redis.RedisService
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.auth.AuthService

fun main() {
    // CFG-1..CFG-4: validate config before any infrastructure init
    Config.validate()

    // Initialize infrastructure
    Database.init(Config.database)
    RedisService.init(Config.redis)
    RateLimiter.init(Config.rateLimit)
    AuthService.init(Config.auth)
    com.taskly.server.email.Mailer.init(Config.smtp)
    com.taskly.server.auth.GoogleSignIn.init(Config.googleClientId)
    com.taskly.server.push.WebPush.init(Config.push.vapidPublicKey, Config.push.vapidPrivateKey, Config.push.vapidSubject)
    // Reminders and the daily digest (WEB-F8)
    com.taskly.server.push.ReminderService.start()
    // Android background wake-ups via FCM (service-account JSON in FCM_SERVER_KEY)
    com.taskly.server.push.DeviceNotifier.init(Config.push.fcmServerKey)

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