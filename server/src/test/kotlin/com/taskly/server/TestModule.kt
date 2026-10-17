package com.taskly.server

import io.ktor.server.application.*
import com.taskly.server.plugins.*

fun Application.moduleForTest() {
    configureSerialization()
    configureStatusPages()
    configureRouting()
}