package com.taskly.shared

class JVMPlatform : Platform {
    override val name: String = "JVM ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()