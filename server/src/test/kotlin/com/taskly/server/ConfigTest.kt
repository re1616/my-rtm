package com.taskly.server

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConfigTest {
    private fun auth(secret: String) = AuthConfig(secret, 15, 60)

    @Test
    fun `dev accepts built-in secret`() {
        validateAuth(auth(Config.DEV_JWT_SECRET), isDev = true)
    }

    @Test
    fun `production rejects built-in secret`() {
        assertFailsWith<IllegalArgumentException> { validateAuth(auth(Config.DEV_JWT_SECRET), isDev = false) }
    }

    @Test
    fun `production rejects short secret`() {
        assertFailsWith<IllegalArgumentException> { validateAuth(auth("short"), isDev = false) }
    }

    @Test
    fun `production accepts strong secret`() {
        validateAuth(auth("x".repeat(48)), isDev = false)
    }
}
