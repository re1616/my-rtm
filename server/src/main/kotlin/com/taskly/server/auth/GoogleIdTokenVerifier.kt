package com.taskly.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64

/** Identity extracted from a verified Google ID token. */
data class GoogleIdentity(val subject: String, val email: String, val emailVerified: Boolean)

/** Source of Google's signing keys by key id (`kid`). Replaceable in tests. */
fun interface GoogleKeySource {
    fun key(kid: String): RSAPublicKey?
}

/**
 * Verifies Google Sign-In ID tokens (RS256): signature against Google's published keys,
 * issuer, audience (our OAuth client id) and expiry.
 */
class GoogleIdTokenVerifier(
    private val clientId: String,
    private val keys: GoogleKeySource,
) {
    fun verify(idToken: String): GoogleIdentity {
        val decoded = try {
            JWT.decode(idToken)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed Google ID token")
        }
        val key = decoded.keyId?.let(keys::key) ?: throw IllegalArgumentException("Unknown Google signing key")
        val verified = try {
            JWT.require(Algorithm.RSA256(key, null))
                .withIssuer("accounts.google.com", "https://accounts.google.com")
                .withAudience(clientId)
                .acceptLeeway(30)
                .build()
                .verify(idToken)
        } catch (e: JWTVerificationException) {
            throw IllegalArgumentException("Invalid Google ID token: ${e.message}")
        }
        val email = verified.getClaim("email").asString() ?: throw IllegalArgumentException("Google account has no email")
        return GoogleIdentity(
            subject = verified.subject ?: throw IllegalArgumentException("Google ID token has no subject"),
            email = email.lowercase().trim(),
            emailVerified = verified.getClaim("email_verified").asBoolean() == true,
        )
    }
}

/** Google's JWKS endpoint, cached for an hour (Google rotates keys roughly daily). */
class HttpGoogleKeySource(
    private val jwksUrl: String = "https://www.googleapis.com/oauth2/v3/certs",
    private val ttl: Duration = Duration.ofHours(1),
) : GoogleKeySource {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    @Volatile private var cache: Map<String, RSAPublicKey> = emptyMap()
    @Volatile private var fetchedAt = 0L

    override fun key(kid: String): RSAPublicKey? {
        val fresh = System.currentTimeMillis() - fetchedAt < ttl.toMillis()
        if (!fresh || kid !in cache) refresh()
        return cache[kid]
    }

    @Synchronized
    private fun refresh() {
        val body = http.send(
            HttpRequest.newBuilder(URI(jwksUrl)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        cache = parseJwks(body)
        fetchedAt = System.currentTimeMillis()
    }

    companion object {
        fun parseJwks(json: String): Map<String, RSAPublicKey> {
            val decoder = Base64.getUrlDecoder()
            val factory = KeyFactory.getInstance("RSA")
            return Json.parseToJsonElement(json).jsonObject["keys"]!!.jsonArray.associate { el ->
                val k = el.jsonObject
                val n = BigInteger(1, decoder.decode(k["n"]!!.jsonPrimitive.content))
                val e = BigInteger(1, decoder.decode(k["e"]!!.jsonPrimitive.content))
                k["kid"]!!.jsonPrimitive.content to (factory.generatePublic(RSAPublicKeySpec(n, e)) as RSAPublicKey)
            }
        }
    }
}

/** Configured verifier, or null when GOOGLE_CLIENT_ID is not set (the endpoint answers 404). */
object GoogleSignIn {
    @Volatile var verifier: GoogleIdTokenVerifier? = null

    fun init(clientId: String) {
        verifier = clientId.takeIf { it.isNotBlank() }?.let { GoogleIdTokenVerifier(it, HttpGoogleKeySource()) }
    }
}
