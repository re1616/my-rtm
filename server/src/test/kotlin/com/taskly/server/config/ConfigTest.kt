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

    // ── CFG-2: FCM and VAPID key validation ──

    @Test
    fun `validation rejects empty fcm key in prod`() {
        val msg = try {
            ConfigValidator.validatePush("", "", "01234567890123456789012345678901")
            null
        } catch (e: IllegalStateException) {
            e.message ?: ""
        }
        if (msg == null) throw AssertionError("Expected IllegalStateException for empty FCM key")
        assertTrue(msg.contains("FCM_SERVER_KEY"))
    }

    @Test
    fun `validation accepts fcm key in prod`() {
        ConfigValidator.validatePush("fcm-server-key-12345", "vapid-pub-key-1234567890123456",
            "01234567890123456789012345678901")
    }

    @Test
    fun `validation rejects short vapid private key in prod`() {
        val msg = try {
            ConfigValidator.validatePush("fcm-key", "vapid-pub-key", "short")
            null
        } catch (e: IllegalStateException) {
            e.message ?: ""
        }
        if (msg == null) throw AssertionError("Expected IllegalStateException for short VAPID key")
        assertTrue(msg.contains("VAPID_PRIVATE_KEY"))
    }

    @Test
    fun `validation rejects empty vapid public key in prod`() {
        val msg = try {
            ConfigValidator.validatePush("fcm-key", "", "01234567890123456789012345678901")
            null
        } catch (e: IllegalStateException) {
            e.message ?: ""
        }
        if (msg == null) throw AssertionError("Expected IllegalStateException for empty VAPID public key")
        assertTrue(msg.contains("VAPID_PUBLIC_KEY"))
    }

    @Test
    fun `validation skips all push checks in dev mode`() {
        // Empty keys should be accepted in dev mode
        ConfigValidator.validatePush("", "", "", isDev = true)
    }
}