package com.taskly.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun platformExists() {
        val p = getPlatform()
        assertTrue(p.name.isNotBlank(), "Platform name should not be blank")
        println("Platform: ${p.name}")
    }
}