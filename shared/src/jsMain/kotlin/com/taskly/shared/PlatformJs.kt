package com.taskly.shared

class JSPlatform : Platform {
    override val name: String = "Kotlin/JS (Node.js)"
}

actual fun getPlatform(): Platform = JSPlatform()