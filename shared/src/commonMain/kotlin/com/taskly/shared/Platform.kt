package com.taskly.shared

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform