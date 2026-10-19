package com.taskly.server.push

import com.sun.net.httpserver.HttpServer
import com.taskly.server.*
import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AND-S4 server side: a change wakes the user's Android devices with an FCM data message.
 * A local server plays both Google's OAuth token endpoint and the FCM v1 API, and checks the
 * service-account JWT signature like Google does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FcmIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private lateinit var server: HttpServer
    private val tokenRequests = CopyOnWriteArrayList<String>()
    private val messages = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    @Volatile private var unregistered = setOf<String>()
    private lateinit var sender: FcmSender

    @BeforeAll
    fun setup() {
        check(Config.database.database.endsWith("_test"))
        Database.init(Config.database)
        RedisService.init(Config.redis)
        RateLimiter.init(Config.rateLimit)
        AuthService.init(Config.auth)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/token") { ex ->
                val form = String(ex.requestBody.readBytes())
                tokenRequests += form
                val assertion = URLDecoder.decode(form.substringAfter("assertion="), Charsets.UTF_8)
                val ok = verify(assertion)
                val body = if (ok) """{"access_token":"ya29.test","expires_in":3600}""" else """{"error":"invalid_grant"}"""
                ex.sendResponseHeaders(if (ok) 200 else 400, body.length.toLong())
                ex.responseBody.use { it.write(body.toByteArray()) }
            }
            createContext("/v1/projects/taskly-test/messages:send") { ex ->
                val auth = ex.requestHeaders.getFirst("Authorization")
                val msg = json.parseToJsonElement(String(ex.requestBody.readBytes())).jsonObject["message"]!!.jsonObject
                val token = msg["token"]!!.jsonPrimitive.content
                messages += auth to msg
                val status = if (token in unregistered) 404 else 200
                val body = if (status == 404) """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}""" else """{"name":"x"}"""
                ex.sendResponseHeaders(status, body.length.toLong())
                ex.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val pem = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded)
        val serviceAccount = """{"type":"service_account","project_id":"taskly-test","client_email":"fcm@taskly-test.iam.gserviceaccount.com",""" +
            """"private_key":"-----BEGIN PRIVATE KEY-----\n${pem.replace("\n", "\\n")}\n-----END PRIVATE KEY-----\n","token_uri":"$base/token"}"""
        sender = FcmSender.fromServiceAccount(serviceAccount, fcmBase = base)!!
    }

    @AfterAll
    fun teardown() {
        DeviceNotifier.fcm = null
        server.stop(0)
        RedisService.close()
        Database.shutdown()
    }

    @BeforeEach
    fun reset() {
        messages.clear()
        tokenRequests.clear()
        unregistered = emptySet()
    }

    private fun verify(jwt: String): Boolean {
        val p = jwt.split('.')
        val claims = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(p[1]))).jsonObject
        if (claims["iss"]!!.jsonPrimitive.content != "fcm@taskly-test.iam.gserviceaccount.com") return false
        if (claims["scope"]!!.jsonPrimitive.content != "https://www.googleapis.com/auth/firebase.messaging") return false
        return Signature.getInstance("SHA256withRSA").run {
            initVerify(keys.public as RSAPublicKey)
            update("${p[0]}.${p[1]}".toByteArray())
            verify(Base64.getUrlDecoder().decode(p[2]))
        }
    }

    @Test
    fun `registered devices get a data message, the OAuth token is reused, dead tokens are dropped`() = testApplication {
        application { moduleForTest() }
        val (token, userId) = client.register("fcm")
        assertEquals(HttpStatusCode.Created, client.postJson("/v1/push/devices", """{"token":"dev-1"}""", token).status)
        assertEquals(HttpStatusCode.Created, client.postJson("/v1/push/devices", """{"token":"dev-2"}""", token).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/push/devices", """{"token":"x","platform":"ios"}""", token).status)

        assertEquals(2, DeviceNotifier.wake(sender, UUID.fromString(userId)))
        val mine = messages.filter { it.second["token"]!!.jsonPrimitive.content.startsWith("dev-") }
        assertEquals(setOf("dev-1", "dev-2"), mine.map { it.second["token"]!!.jsonPrimitive.content }.toSet())
        assertTrue(mine.all { it.first == "Bearer ya29.test" })
        assertEquals("sync", mine.first().second["data"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", mine.first().second["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content)

        unregistered = setOf("dev-2")
        assertEquals(1, DeviceNotifier.wake(sender, UUID.fromString(userId)))
        messages.clear()
        assertEquals(1, DeviceNotifier.wake(sender, UUID.fromString(userId)), "the unregistered token was removed")
        assertTrue(tokenRequests.size <= 1, "the access token is cached (another test may have fetched it already)")
    }

    @Test
    fun `a sync change wakes the owner's devices, not other users'`() = testApplication {
        application { moduleForTest() }
        DeviceNotifier.fcm = sender
        DeviceNotifier.throttleMillis = 0
        val (mine, myId) = client.register("wake")
        val (other, _) = client.register("wake-other")
        client.postJson("/v1/push/devices", """{"token":"mine-1"}""", mine)
        client.postJson("/v1/push/devices", """{"token":"other-1"}""", other)

        val op = """{"opId":"${UUID.randomUUID()}","scope":"user:$myId","entity":"task","entityId":"${UUID.randomUUID()}","type":"create","fields":{"name":"x"}}"""
        assertEquals(HttpStatusCode.OK, client.postJson("/v1/sync", """{"ops":[$op]}""", mine).status)
        var waited = 0
        while (messages.none { it.second["token"]!!.jsonPrimitive.content == "mine-1" } && waited < 5_000) {
            delay(100); waited += 100
        }
        assertTrue(messages.any { it.second["token"]!!.jsonPrimitive.content == "mine-1" })
        assertTrue(messages.none { it.second["token"]!!.jsonPrimitive.content == "other-1" })
        DeviceNotifier.fcm = null
    }

    @Test
    fun `devices without exact alarms get reminders from the server (AND-N2)`() = testApplication {
        application { moduleForTest() }
        DeviceNotifier.fcm = sender
        val (token, userId) = client.register("inexact")
        client.postJson("/v1/push/devices", """{"token":"inexact-1","exactAlarms":false}""", token)
        client.postJson("/v1/push/devices", """{"token":"exact-1","exactAlarms":true}""", token)
        val due = java.time.Instant.now().plusSeconds(600)
        val op = """{"opId":"${UUID.randomUUID()}","scope":"user:$userId","entity":"task","entityId":"${UUID.randomUUID()}","type":"create","fields":{"name":"Платёж","dueAt":"$due","dueHasTime":true}}"""
        client.postJson("/v1/sync", """{"ops":[$op]}""", token)
        messages.clear()

        ReminderService.tick(java.time.Instant.now())
        val reminders = messages.filter { it.second["data"]!!.jsonObject["type"]!!.jsonPrimitive.content == "reminder" }
        assertEquals(listOf("inexact-1"), reminders.map { it.second["token"]!!.jsonPrimitive.content })
        assertEquals("Платёж", reminders.single().second["data"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        DeviceNotifier.fcm = null
    }

    private suspend fun HttpClient.register(prefix: String): Pair<String, String> {
        val r = postJson("/v1/auth/register", """{"email":"$prefix-${System.nanoTime()}@test.taskly","password":"Password123"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val o = json.parseToJsonElement(r.bodyAsText()).jsonObject
        return o["accessToken"]!!.jsonPrimitive.content to o["userId"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); token?.let { bearerAuth(it) }; setBody(body) }
}
