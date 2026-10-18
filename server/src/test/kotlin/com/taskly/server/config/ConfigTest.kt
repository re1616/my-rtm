package com.taskly.server.config

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ConfigTest {

    @Test
    fun `validation rejects short jwt secret`() {
        val msg = try {
            ConfigValidator.validateAuth("short", "dev")
            null
        } catch (e: IllegalStateException) {
            e.message ?: ""
        }
        if (msg == null) throw AssertionError("Expected IllegalStateException for short secret")
        assertTrue(msg.contains("too short"))
    }

    @Test
    fun `validation rejects dev jwt secret outside dev mode`() {
        val msg = try {
            ConfigValidator.validateAuth("taskly-dev-secret-change-in-production-min-256bits!!", "prod")
            null
        } catch (e: IllegalStateException) {
            e.message ?: ""
        }
        if (msg == null) throw AssertionError("Expected IllegalStateException for dev secret in prod mode")
        assertTrue(msg.contains("development default"))
        assertTrue(msg.contains("prod"))
    }

    @Test
    fun `validation allows dev jwt secret in dev mode`() {
        ConfigValidator.validateAuth("taskly-dev-secret-change-in-production-min-256bits!!", "dev")
    }

    @Test
    fun `validation accepts strong jwt secret`() {
        ConfigValidator.validateAuth("abcdefghijklmnopqrstuvwxyz0123456789abcd", "prod")
    }

    @Test
    fun `validation accepts strong 32-char secret`() {
        ConfigValidator.validateAuth("abcdefghijklmnopqrstuvwxyz012345", "prod")
    }

    @Test
    fun `validation rejects db name with test but not ending with _test`() {
        val msg = try {
            ConfigValidator.validateDatabase("test_db_public")
            null
        } catch (e: IllegalStateException) {
            e.message ?: ""
        }
        if (msg == null) throw AssertionError("Expected IllegalStateException for bad db name")
        assertTrue(msg.contains("_test"))
    }

    @Test
    fun `validation accepts db name ending with _test`() {
        ConfigValidator.validateDatabase("taskly_test")
    }

    @Test
    fun `validation accepts production db name without test`() {
        ConfigValidator.validateDatabase("taskly_prod")
    }

    @Test
    fun `validation accepts empty db name`() {
        ConfigValidator.validateDatabase("")
    }
}