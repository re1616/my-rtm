package com.taskly.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import com.taskly.server.plugins.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureSerialization()
        configureStatusPages()
        configureRouting()
    }.start(wait = true)
}