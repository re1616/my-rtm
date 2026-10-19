package com.taskly.server.push

import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push without third-party libraries (WEB-F8):
 *  - payload encryption "aes128gcm" (RFC 8291 + RFC 8188),
 *  - VAPID authorization with an ES256 JWT (RFC 8292).
 * Keys use the formats browsers and the `web-push` tools use: base64url of the uncompressed
 * P-256 point (65 bytes) for public keys and of the 32-byte scalar for the private key.
 */
object WebPushCrypto {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    fun b64url(bytes: ByteArray): String = b64.encodeToString(bytes)
    fun unb64url(s: String): ByteArray = b64d.decode(s.trim().trimEnd('=').replace('+', '-').replace('/', '_'))

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** Uncompressed point 0x04 || X || Y. */
    fun encodePublic(key: ECPublicKey): ByteArray {
        fun fixed(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    fun decodePublic(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "Expected an uncompressed P-256 point" }
        val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, P256)) as ECPublicKey
    }

    fun decodePrivate(bytes: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, bytes), P256)) as ECPrivateKey

    fun encodePrivate(key: ECPrivateKey): ByteArray {
        val raw = key.s.toByteArray()
        return if (raw.size >= 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun ecdh(priv: ECPrivateKey, pub: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }

    private data class Keys(val cek: ByteArray, val nonce: ByteArray)

    /** RFC 8291 §3.4: content-encryption key and nonce from the ECDH secret. */
    private fun derive(ecdhSecret: ByteArray, authSecret: ByteArray, uaPublic: ByteArray, asPublic: ByteArray, salt: ByteArray): Keys {
        val prkKey = hmac(authSecret, ecdhSecret)
        val keyInfo = "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublic + asPublic
        val ikm = hmac(prkKey, keyInfo + byteArrayOf(1))
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
        return Keys(cek, nonce)
    }

    /** Encrypts [payload] for a subscription (single record, rs = 4096). */
    fun encrypt(payload: ByteArray, uaPublic: ByteArray, authSecret: ByteArray, asKeys: KeyPair = newKeyPair(), salt: ByteArray = ByteArray(16).also(random::nextBytes)): ByteArray {
        require(payload.size <= 3993) { "Web Push payload too large" }
        val asPublic = encodePublic(asKeys.public as ECPublicKey)
        val secret = ecdh(asKeys.private as ECPrivateKey, decodePublic(uaPublic))
        val k = derive(secret, authSecret, uaPublic, asPublic, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.cek, "AES"), GCMParameterSpec(128, k.nonce))
        }
        val body = cipher.doFinal(payload + byteArrayOf(2)) // 0x02 = last record, no padding
        val header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.size)
            .put(salt).putInt(4096).put(asPublic.size.toByte()).put(asPublic).array()
        return header + body
    }

    /** The user agent's side (used by tests to prove that what we send can be read). */
    fun decrypt(message: ByteArray, uaPrivate: ECPrivateKey, uaPublic: ByteArray, authSecret: ByteArray): ByteArray {
        val salt = message.copyOfRange(0, 16)
        val idLen = message[20].toInt() and 0xff
        val asPublic = message.copyOfRange(21, 21 + idLen)
        val secret = ecdh(uaPrivate, decodePublic(asPublic))
        val k = derive(secret, authSecret, uaPublic, asPublic, salt)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(k.cek, "AES"), GCMParameterSpec(128, k.nonce))
            doFinal(message.copyOfRange(21 + idLen, message.size))
        }
        var end = plain.size - 1
        while (end >= 0 && plain[end] == 0.toByte()) end--
        require(end >= 0 && plain[end] == 2.toByte()) { "Missing record delimiter" }
        return plain.copyOf(end)
    }

    /** VAPID ES256 JWT for the push service at [audience] (scheme://host). */
    fun vapidJwt(audience: String, subject: String, privateKey: ECPrivateKey, nowEpochSeconds: Long): String {
        val header = b64url("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = b64url("""{"aud":"$audience","exp":${nowEpochSeconds + 12 * 3600},"sub":"$subject"}""".toByteArray())
        val signingInput = "$header.$claims"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray())
            sign()
        }
        return "$signingInput.${b64url(derToJose(der))}"
    }

    /** DER ECDSA signature → JOSE r || s (32 + 32 bytes). */
    fun derToJose(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun int(): ByteArray {
            require(der[i] == 0x02.toByte()); val len = der[i + 1].toInt(); val v = der.copyOfRange(i + 2, i + 2 + len); i += 2 + len
            val trimmed = v.dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - trimmed.size) + trimmed
        }
        return int() + int()
    }

    /** JOSE r || s → DER (to verify signatures with the JDK in tests). */
    fun joseToDer(jose: ByteArray): ByteArray {
        fun der(v: ByteArray): ByteArray {
            var t = v.dropWhile { it == 0.toByte() }.toByteArray()
            if (t.isEmpty()) t = byteArrayOf(0)
            if (t[0].toInt() and 0x80 != 0) t = byteArrayOf(0) + t
            return byteArrayOf(2, t.size.toByte()) + t
        }
        val body = der(jose.copyOfRange(0, 32)) + der(jose.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

data class PushSubscriptionKeys(val endpoint: String, val p256dh: String, val auth: String)

enum class PushOutcome { SENT, GONE, FAILED }

/** Sends one notification; injectable in tests. */
fun interface PushSender {
    fun send(subscription: PushSubscriptionKeys, payload: String): PushOutcome
}

class VapidPushSender(
    private val publicKey: ByteArray,
    private val privateKey: ECPrivateKey,
    private val subject: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : PushSender {
    private val log = LoggerFactory.getLogger("WebPush")

    override fun send(subscription: PushSubscriptionKeys, payload: String): PushOutcome {
        if (!PushEndpoints.isAllowed(subscription.endpoint)) return PushOutcome.GONE
        return try {
            val uri = URI(subscription.endpoint)
            val audience = "${uri.scheme}://${uri.authority}"
            val body = WebPushCrypto.encrypt(payload.toByteArray(), WebPushCrypto.unb64url(subscription.p256dh), WebPushCrypto.unb64url(subscription.auth))
            val jwt = WebPushCrypto.vapidJwt(audience, subject, privateKey, System.currentTimeMillis() / 1000)
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("TTL", "86400")
                .header("Urgency", "high")
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("Authorization", "vapid t=$jwt, k=${WebPushCrypto.b64url(publicKey)}")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            when (status) {
                in 200..299 -> PushOutcome.SENT
                404, 410 -> PushOutcome.GONE // subscription expired or revoked
                else -> PushOutcome.FAILED.also { log.warn("Push service answered {}", status) }
            }
        } catch (e: Exception) {
            log.warn("Push failed: {}", e.message)
            PushOutcome.FAILED
        }
    }
}

/**
 * The server POSTs to URLs supplied by clients, so only real push services are allowed
 * (no SSRF into the internal network). Tests add their local endpoint explicitly.
 */
object PushEndpoints {
    private val defaultSuffixes = listOf(
        "fcm.googleapis.com",
        "push.services.mozilla.com",
        "notify.windows.com",
        "push.apple.com",
    )

    @Volatile
    var extraAllowed: (URI) -> Boolean = { false }

    fun isAllowed(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        if (endpoint.length > 2048) return false
        if (extraAllowed(uri)) return true
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && defaultSuffixes.any { host == it || host.endsWith(".$it") }
    }
}

/** Configured sender; null when VAPID keys are not set (Web Push off). */
object WebPush {
    @Volatile
    var sender: PushSender? = null

    @Volatile
    var publicKey: String? = null

    fun init(publicKeyB64: String, privateKeyB64: String, subject: String) {
        if (publicKeyB64.isBlank() || privateKeyB64.isBlank()) {
            sender = null
            publicKey = null
            return
        }
        val pub = WebPushCrypto.unb64url(publicKeyB64)
        WebPushCrypto.decodePublic(pub) // validates the key
        sender = VapidPushSender(pub, WebPushCrypto.decodePrivate(WebPushCrypto.unb64url(privateKeyB64)), subject)
        publicKey = WebPushCrypto.b64url(pub)
    }
}
