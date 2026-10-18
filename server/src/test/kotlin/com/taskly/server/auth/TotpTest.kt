package com.taskly.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC 6238 appendix B test vectors (SHA-1, secret "12345678901234567890"), 6-digit codes. */
class TotpTest {
    private val secret = Base32.encode("12345678901234567890".toByteArray())

    @Test
    fun `matches RFC 6238 vectors`() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", secret)
        assertEquals("287082", Totp.code(secret, 59))
        assertEquals("081804", Totp.code(secret, 1111111109))
        assertEquals("050471", Totp.code(secret, 1111111111))
        assertEquals("005924", Totp.code(secret, 1234567890))
        assertEquals("279037", Totp.code(secret, 2000000000))
    }

    @Test
    fun `accepts one step of clock drift and nothing more`() {
        val now = 1_700_000_000L
        assertTrue(Totp.verify(secret, Totp.code(secret, now - 30), now))
        assertTrue(Totp.verify(secret, Totp.code(secret, now + 30), now))
        assertFalse(Totp.verify(secret, Totp.code(secret, now - 90), now))
        assertFalse(Totp.verify(secret, "12345", now))
        assertFalse(Totp.verify(secret, null, now))
    }

    @Test
    fun `base32 round trip`() {
        val bytes = ByteArray(20) { it.toByte() }
        assertTrue(bytes.contentEquals(Base32.decode(Base32.encode(bytes))))
    }
}
