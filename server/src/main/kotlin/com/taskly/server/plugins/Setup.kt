package com.taskly.server.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}

fun Application.configureStatusPages() {
    // Stub: will be populated with error handling
}

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "taskly"))
        }
    }
}