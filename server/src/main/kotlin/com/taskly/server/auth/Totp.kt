package com.taskly.server.auth

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238, HMAC-SHA1, 6 digits, 30-second step) — the format
 * every authenticator app (Google Authenticator, 1Password, Aegis…) understands.
 */
object Totp {
    private const val DIGITS = 6
    private const val STEP_SECONDS = 30L
    private val random = SecureRandom()

    /** New 160-bit secret, Base32-encoded. */
    fun newSecret(): String = Base32.encode(ByteArray(20).also(random::nextBytes))

    /** otpauth:// URI for QR codes in authenticator apps. */
    fun uri(secret: String, account: String, issuer: String = "Taskly"): String {
        val label = enc("$issuer:$account")
        return "otpauth://totp/$label?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    /** Code for the time step containing [epochSeconds]. */
    fun code(secret: String, epochSeconds: Long): String {
        val counter = Math.floorDiv(epochSeconds, STEP_SECONDS)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(Base32.decode(secret), "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /** Accepts the current step and one step either side to tolerate clock drift. */
    fun verify(secret: String, code: String?, epochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val candidate = code?.filter(Char::isDigit) ?: return false
        if (candidate.length != DIGITS) return false
        return (-1..1).any { drift -> constantTimeEquals(code(secret, epochSeconds + drift * STEP_SECONDS), candidate) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}

/** RFC 4648 Base32 without padding (the alphabet authenticator apps expect). */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.uppercase().filter { it != '=' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid Base32 character: $c" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
