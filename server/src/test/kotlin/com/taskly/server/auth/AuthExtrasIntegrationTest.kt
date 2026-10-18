package com.taskly.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.taskly.server.*
import com.taskly.server.db.Database
import com.taskly.server.email.Email
import com.taskly.server.email.EmailSender
import com.taskly.server.email.LogEmailSender
import com.taskly.server.email.Mailer
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** WEB-S3 on the server: password reset, TOTP two-factor auth, Google sign-in — through the HTTP API. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthExtrasIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val sent = CopyOnWriteArrayList<Email>()

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pub = keys.public as RSAPublicKey
    private val priv = keys.private as RSAPrivateKey
    private val clientId = "taskly-test.apps.googleusercontent.com"

    @BeforeAll
    fun setup() {
        check(Config.database.database.endsWith("_test")) { "Integration tests must use a *_test database (PGDATABASE)" }
        Database.init(Config.database)
        RedisService.init(Config.redis)
        RateLimiter.init(Config.rateLimit)
        AuthService.init(Config.auth)
        Mailer.sender = EmailSender { sent += it }
        GoogleSignIn.verifier = GoogleIdTokenVerifier(clientId) { kid -> if (kid == "test-key") pub else null }
    }

    @BeforeEach
    fun clearMail() = sent.clear()

    @AfterEach
    fun resetGoogle() {
        GoogleSignIn.verifier = GoogleIdTokenVerifier(clientId) { kid -> if (kid == "test-key") pub else null }
    }

    @AfterAll
    fun teardown() {
        Mailer.sender = LogEmailSender()
        GoogleSignIn.verifier = null
        RedisService.close()
        Database.shutdown()
    }

    // ── Password reset ──

    @Test
    fun `password reset sets a new password once and signs out other sessions`() = testApplication {
        application { moduleForTest() }
        val email = unique("reset")
        val before = client.register(email, "OldPassword1")

        assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/auth/password/forgot", """{"email":"$email"}""").status)
        val mail = sent.single { it.to == email }
        val token = Regex("token=([A-Za-z0-9_-]+)").find(mail.text)!!.groupValues[1]

        assertEquals(HttpStatusCode.OK, client.postJson("/v1/auth/password/reset", """{"token":"$token","newPassword":"NewPassword2"}""").status)

        assertEquals(HttpStatusCode.Unauthorized, client.login(email, "OldPassword1").status)
        assertEquals(HttpStatusCode.OK, client.login(email, "NewPassword2").status)
        // Sessions issued before the reset are revoked
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", """{"refreshToken":"${before.str("refreshToken")}"}""").status)
        // The link works only once
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/auth/password/reset", """{"token":"$token","newPassword":"Another3pass"}""").status)
    }

    @Test
    fun `forgot password does not reveal unknown emails and rejects short passwords`() = testApplication {
        application { moduleForTest() }
        assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/auth/password/forgot", """{"email":"${unique("nobody")}"}""").status)
        assertTrue(sent.isEmpty(), "no email for an unregistered address")

        val email = unique("short")
        client.register(email, "OldPassword1")
        client.postJson("/v1/auth/password/forgot", """{"email":"$email"}""")
        val token = Regex("token=([A-Za-z0-9_-]+)").find(sent.single().text)!!.groupValues[1]
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/auth/password/reset", """{"token":"$token","newPassword":"short"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/auth/password/reset", """{"token":"forged","newPassword":"LongEnough1"}""").status)
    }

    // ── Two-factor authentication ──

    @Test
    fun `two-factor auth is required at login once enabled`() = testApplication {
        application { moduleForTest() }
        val email = unique("totp")
        val token = client.register(email, "Password123").str("accessToken")

        val setup = client.post("/v1/auth/2fa/setup") { bearerAuth(token) }.obj()
        val secret = setup.str("secret")
        assertTrue(setup.str("otpauthUri").startsWith("otpauth://totp/"))

        // Not active until confirmed with a valid code
        assertEquals(HttpStatusCode.OK, client.login(email, "Password123").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/auth/2fa/enable", """{"code":"000000"}""", token).status)
        assertEquals(HttpStatusCode.OK, client.postJson("/v1/auth/2fa/enable", """{"code":"${now(secret)}"}""", token).status)
        assertTrue(client.get("/v1/auth/account") { bearerAuth(token) }.obj()["totpEnabled"]!!.jsonPrimitive.boolean)

        val noCode = client.login(email, "Password123")
        assertEquals(HttpStatusCode.Unauthorized, noCode.status)
        assertEquals("totp_required", noCode.obj().str("code"))

        val wrong = client.login(email, "Password123", "999999".takeIf { it != now(secret) } ?: "888888")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("totp_invalid", wrong.obj().str("code"))

        assertEquals(HttpStatusCode.OK, client.login(email, "Password123", now(secret)).status)

        // Disabling needs a valid code too
        assertEquals(HttpStatusCode.OK, client.postJson("/v1/auth/2fa/disable", """{"code":"${now(secret)}"}""", token).status)
        assertEquals(HttpStatusCode.OK, client.login(email, "Password123").status)
    }

    // ── Google sign-in ──

    @Test
    fun `google sign-in creates an account, then signs into the same one`() = testApplication {
        application { moduleForTest() }
        val email = unique("google")
        val first = client.postJson("/v1/auth/google", """{"idToken":"${idToken("sub-1-$email", email)}"}""")
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val again = client.postJson("/v1/auth/google", """{"idToken":"${idToken("sub-1-$email", email)}"}""")
        assertEquals(first.obj().str("userId"), again.obj().str("userId"))

        val account = client.get("/v1/auth/account") { bearerAuth(again.obj().str("accessToken")) }.obj()
        assertEquals(false, account["hasPassword"]!!.jsonPrimitive.boolean)
        assertEquals(true, account["googleLinked"]!!.jsonPrimitive.boolean)
        // A Google-only account cannot be entered with an empty or guessed password
        assertEquals(HttpStatusCode.Unauthorized, client.login(email, "").status)
    }

    @Test
    fun `google sign-in links an existing password account with the same verified email`() = testApplication {
        application { moduleForTest() }
        val email = unique("link")
        val existing = client.register(email, "Password123").str("userId")
        val viaGoogle = client.postJson("/v1/auth/google", """{"idToken":"${idToken("sub-link-$email", email)}"}""").obj()
        assertEquals(existing, viaGoogle.str("userId"))
    }

    @Test
    fun `google sign-in rejects forged, foreign and unverified tokens`() = testApplication {
        application { moduleForTest() }
        val email = unique("bad")
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val forged = JWT.create().withKeyId("test-key").withIssuer("https://accounts.google.com").withAudience(clientId)
            .withSubject("x").withClaim("email", email).withClaim("email_verified", true)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(300)))
            .sign(Algorithm.RSA256(otherKey.public as RSAPublicKey, otherKey.private as RSAPrivateKey))

        for (token in listOf(
            forged,
            idToken("x", email, audience = "someone-else"),
            idToken("x", email, verified = false),
            idToken("x", email, expiresIn = -3600),
            "not-a-jwt",
        )) {
            val r = client.postJson("/v1/auth/google", """{"idToken":"$token"}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        }
        assertNotEquals(HttpStatusCode.OK, client.login(email, "anything").status)
    }

    @Test
    fun `google sign-in answers 404 when it is not configured`() = testApplication {
        application { moduleForTest() }
        GoogleSignIn.verifier = null
        val r = client.postJson("/v1/auth/google", """{"idToken":"${idToken("s", unique("off"))}"}""")
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // ── Helpers ──

    private fun idToken(sub: String, email: String, audience: String = clientId, verified: Boolean = true, expiresIn: Long = 300): String =
        JWT.create()
            .withKeyId("test-key")
            .withIssuer("https://accounts.google.com")
            .withAudience(audience)
            .withSubject(sub)
            .withClaim("email", email)
            .withClaim("email_verified", verified)
            .withIssuedAt(Date.from(Instant.now().minusSeconds(10)))
            .withExpiresAt(Date.from(Instant.now().plusSeconds(expiresIn)))
            .sign(Algorithm.RSA256(pub, priv))

    private fun now(secret: String) = Totp.code(secret, System.currentTimeMillis() / 1000)

    private fun unique(prefix: String) = "$prefix-${System.nanoTime()}@test.taskly"

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            token?.let { bearerAuth(it) }
            setBody(body)
        }

    private suspend fun HttpClient.register(email: String, password: String): JsonObject {
        val r = postJson("/v1/auth/register", """{"email":"$email","password":"$password"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.obj()
    }

    private suspend fun HttpClient.login(email: String, password: String, totp: String? = null): HttpResponse =
        postJson("/v1/auth/login", """{"email":"$email","password":"$password"${totp?.let { ",\"totpCode\":\"$it\"" } ?: ""}}""")

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
