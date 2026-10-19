package com.taskly.server.push

import com.sun.net.httpserver.HttpServer
import com.taskly.server.*
import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.email.Email
import com.taskly.server.email.EmailSender
import com.taskly.server.email.Mailer
import com.taskly.server.ratelimit.RateLimiter
import com.taskly.server.redis.RedisService
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WEB-F8 through the public API: Web Push subscription, reminders at the configured offsets
 * (sent once), encrypted payload readable by the browser, VAPID signature, daily email digest;
 * WEB-F9: settings validation, export, account deletion.
 * A local HTTP server stands in for the push service; the test decrypts what it receives with
 * the browser's private key — exactly what a real browser does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemindersIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class Received(val path: String, val headers: Map<String, String>, val body: ByteArray)

    private val received = CopyOnWriteArrayList<Received>()
    private val emails = CopyOnWriteArrayList<Email>()
    @Volatile private var pushStatus = 201
    private lateinit var pushServer: HttpServer
    private lateinit var vapidPublic: ByteArray

    @BeforeAll
    fun setup() {
        check(Config.database.database.endsWith("_test")) { "Integration tests must use a *_test database (PGDATABASE)" }
        Database.init(Config.database)
        RedisService.init(Config.redis)
        RateLimiter.init(Config.rateLimit)
        AuthService.init(Config.auth)

        val vapid = WebPushCrypto.newKeyPair()
        vapidPublic = WebPushCrypto.encodePublic(vapid.public as ECPublicKey)
        WebPush.init(
            WebPushCrypto.b64url(vapidPublic),
            WebPushCrypto.b64url(WebPushCrypto.encodePrivate(vapid.private as ECPrivateKey)),
            "mailto:ops@taskly.test",
        )
        PushEndpoints.extraAllowed = { it.host == "127.0.0.1" && it.port == pushServer.address.port }
        pushServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                received += Received(ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }, ex.requestBody.readBytes())
                ex.sendResponseHeaders(pushStatus, -1)
                ex.close()
            }
            start()
        }
        Mailer.sender = EmailSender { emails += it }
    }

    @AfterAll
    fun teardown() {
        pushServer.stop(0)
        PushEndpoints.extraAllowed = { false }
        RedisService.close()
        Database.shutdown()
    }

    @BeforeEach
    fun reset() {
        received.clear()
        emails.clear()
        pushStatus = 201
    }

    // ── Browser side of a push subscription ──
    private class Browser(val endpoint: String) {
        val keys = WebPushCrypto.newKeyPair()
        val publicKey: ByteArray = WebPushCrypto.encodePublic(keys.public as ECPublicKey)
        val auth = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        fun subscriptionJson() =
            """{"endpoint":"$endpoint","keys":{"p256dh":"${WebPushCrypto.b64url(publicKey)}","auth":"${WebPushCrypto.b64url(auth)}"}}"""
        fun read(body: ByteArray): String = String(WebPushCrypto.decrypt(body, keys.private as ECPrivateKey, publicKey, auth))
    }

    private fun browser() = Browser("http://127.0.0.1:${pushServer.address.port}/push/${UUID.randomUUID()}")

    @Test
    fun `a timed task gets one encrypted push and one email at the default offset`() = testApplication {
        application { moduleForTest() }
        val u = client.register("remind")
        client.settings(u, """{"timeZone":"UTC","reminderOffsets":[15],"emailReminders":true}""")
        val b = browser()
        assertEquals(HttpStatusCode.Created, client.postJson("/v1/push/subscriptions", b.subscriptionJson(), u.token).status)

        val due = Instant.now().plusSeconds(600) // fires 15 min before = 5 min ago
        val task = client.newTask(u, "Позвонить в банк", """"dueAt":"${iso(due)}","dueHasTime":true""")
        val done = client.newTask(u, "Уже сделано", """"dueAt":"${iso(due)}","dueHasTime":true""")
        client.sync(u, "task", done, "complete")

        ReminderService.tick(Instant.now())
        val mine = received.filter { it.path == java.net.URI(b.endpoint).path }
        assertEquals(1, mine.size, "one push for the open task, none for the completed one")
        val push = mine.single()
        assertEquals("aes128gcm", push.headers["content-encoding"])
        val payload = json.parseToJsonElement(b.read(push.body)).jsonObject
        assertEquals("Позвонить в банк", payload.str("title"))
        assertEquals(task, payload.str("taskId"))
        verifyVapid(push.headers["authorization"]!!, "http://127.0.0.1:${pushServer.address.port}")
        assertEquals(1, emails.count { it.subject.contains("Позвонить в банк") })

        // Sent once: the next tick (or another instance) does not repeat it
        ReminderService.tick(Instant.now())
        assertEquals(1, received.count { it.path == java.net.URI(b.endpoint).path })
        assertEquals(1, emails.count { it.subject.contains("Позвонить в банк") })
    }

    @Test
    fun `a date-only task is reminded at the configured time of day; push off means no push`() = testApplication {
        application { moduleForTest() }
        val u = client.register("dayonly")
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        if (now.hour == 0 && now.minute < 5) return@testApplication // the minute before midnight is yesterday
        val dayTime = now.minusMinutes(2).format(DateTimeFormatter.ofPattern("HH:mm"))
        client.settings(u, """{"timeZone":"UTC","reminderDayTime":"$dayTime","pushReminders":false,"emailReminders":true}""")
        val b = browser()
        client.postJson("/v1/push/subscriptions", b.subscriptionJson(), u.token)
        val midnight = now.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
        client.newTask(u, "Оплатить счёт", """"dueAt":"${iso(midnight)}","dueHasTime":false""")

        ReminderService.tick(Instant.now())
        assertEquals(1, emails.count { it.subject.contains("Оплатить счёт") })
        assertTrue(received.none { it.path == java.net.URI(b.endpoint).path }, "push reminders are switched off")
    }

    @Test
    fun `expired subscriptions are dropped and foreign push endpoints are refused`() = testApplication {
        application { moduleForTest() }
        val u = client.register("gone")
        val b = browser()
        client.postJson("/v1/push/subscriptions", b.subscriptionJson(), u.token)
        pushStatus = 410
        val test1 = client.postJson("/v1/push/test", "{}", u.token).obj()
        assertEquals(0, test1["sent"]!!.jsonPrimitive.content.toInt())
        pushStatus = 201
        val test2 = client.postJson("/v1/push/test", "{}", u.token).obj()
        assertEquals(0, test2["sent"]!!.jsonPrimitive.content.toInt(), "the 410 subscription was removed")
        assertEquals(1, received.count { it.path == java.net.URI(b.endpoint).path })

        // SSRF: the server must not POST to arbitrary URLs supplied by a client
        val evil = Browser("http://169.254.169.254/latest/meta-data")
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/push/subscriptions", evil.subscriptionJson(), u.token).status)
        val badKey = """{"endpoint":"${b.endpoint}","keys":{"p256dh":"AAAA","auth":"${WebPushCrypto.b64url(b.auth)}"}}"""
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/v1/push/subscriptions", badKey, u.token).status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/push/vapid-key") { bearerAuth(u.token) }.status)
    }

    @Test
    fun `daily digest lists today and overdue tasks once a day`() = testApplication {
        application { moduleForTest() }
        val u = client.register("digest")
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        if (now.hour == 0 && now.minute < 5) return@testApplication
        val at = now.minusMinutes(1).format(DateTimeFormatter.ofPattern("HH:mm"))
        client.settings(u, """{"timeZone":"UTC","digestEnabled":true,"digestTime":"$at","pushReminders":false}""")
        client.newTask(u, "Сегодняшняя", """"dueAt":"${iso(now.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC))}"""")
        client.newTask(u, "Старая", """"dueAt":"${iso(Instant.now().minusSeconds(3 * 86400))}"""")
        client.newTask(u, "Будущая", """"dueAt":"${iso(Instant.now().plusSeconds(5 * 86400))}"""")

        ReminderService.tick(Instant.now())
        val digest = emails.single { it.subject.startsWith("Taskly: задачи") && it.text.contains("Сегодняшняя") }
        assertTrue(digest.text.contains("Старая"))
        assertTrue(!digest.text.contains("Будущая"))
        ReminderService.tick(Instant.now())
        assertEquals(1, emails.count { it.subject.startsWith("Taskly: задачи") && it.text.contains("Сегодняшняя") })
    }

    @Test
    fun `settings are validated, export has everything, deletion needs the password`() = testApplication {
        application { moduleForTest() }
        val u = client.register("account")
        assertEquals(HttpStatusCode.BadRequest, client.patchJson("/v1/account/settings", """{"timeZone":"Mars/Base"}""", u.token).status)
        assertEquals(HttpStatusCode.BadRequest, client.patchJson("/v1/account/settings", """{"digestTime":"25:00"}""", u.token).status)
        val s = client.settings(u, """{"timeZone":"Asia/Tokyo","locale":"en","theme":"dark","reminderOffsets":[60,10]}""")
        assertEquals("Asia/Tokyo", s.str("timeZone"))
        assertEquals("en", s.str("locale"))
        assertEquals(listOf(10, 60), s["reminderOffsets"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })

        val task = client.newTask(u, "Экспортируй меня", "")
        client.sync(u, "note", UUID.randomUUID().toString(), "create", """{"taskId":"$task","content":"заметка"}""")
        val export = client.get("/v1/account/export") { bearerAuth(u.token) }.obj()
        assertEquals("taskly-export/1", export.str("format"))
        assertTrue(export["tasks"]!!.jsonArray.any { it.jsonObject.str("name") == "Экспортируй меня" })
        assertTrue(export["notes"]!!.jsonArray.any { it.jsonObject.str("content") == "заметка" })

        val other = client.register("bystander")
        val otherTask = client.newTask(other, "Чужая задача", "")

        assertEquals(HttpStatusCode.Unauthorized, client.deleteJson("/v1/account", """{"password":"wrong"}""", u.token).status)
        assertEquals(HttpStatusCode.NoContent, client.deleteJson("/v1/account", """{"password":"Password123"}""", u.token).status)
        val login = client.postJson("/v1/auth/login", """{"email":"${u.email}","password":"Password123"}""")
        assertTrue(login.status == HttpStatusCode.Unauthorized || login.status == HttpStatusCode.BadRequest, "the account is gone")
        assertEquals(HttpStatusCode.OK, client.get("/v1/tasks/$otherTask") { bearerAuth(other.token) }.status, "other users are untouched")
    }

    // ── Helpers ──

    private fun verifyVapid(header: String, audience: String) {
        val m = Regex("""vapid t=([^,]+), k=(\S+)""").find(header) ?: throw AssertionError("bad Authorization: $header")
        val (jwt, k) = m.destructured
        assertEquals(WebPushCrypto.b64url(vapidPublic), k)
        val parts = jwt.split('.')
        val claims = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
        assertEquals(audience, claims.str("aud"))
        assertEquals("mailto:ops@taskly.test", claims.str("sub"))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(WebPushCrypto.decodePublic(vapidPublic))
            update("${parts[0]}.${parts[1]}".toByteArray())
            verify(WebPushCrypto.joseToDer(Base64.getUrlDecoder().decode(parts[2])))
        }
        assertTrue(ok, "VAPID JWT signature must verify with the server's public key")
    }

    private fun iso(i: Instant) = DateTimeFormatter.ISO_INSTANT.format(i)

    private data class User(val token: String, val id: String, val email: String)

    private suspend fun HttpClient.register(prefix: String): User {
        val email = "$prefix-${System.nanoTime()}@test.taskly"
        val r = postJson("/v1/auth/register", """{"email":"$email","password":"Password123"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val o = r.obj()
        return User(o.str("accessToken"), o.str("userId"), email)
    }

    private suspend fun HttpClient.settings(u: User, body: String): JsonObject {
        val r = patchJson("/v1/account/settings", body, u.token)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return r.obj()
    }

    private suspend fun HttpClient.newTask(u: User, name: String, extra: String): String {
        val id = UUID.randomUUID().toString()
        val fields = if (extra.isBlank()) """{"name":"$name"}""" else """{"name":"$name",$extra}"""
        val r = sync(u, "task", id, "create", fields)
        assertEquals("ok", r, "create $name")
        return id
    }

    private suspend fun HttpClient.sync(u: User, entity: String, id: String, type: String, fields: String = "{}"): String {
        val op = """{"opId":"${UUID.randomUUID()}","scope":"user:${u.id}","entity":"$entity","entityId":"$id","type":"$type","fields":$fields}"""
        val r = postJson("/v1/sync", """{"ops":[$op]}""", u.token)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return r.obj()["results"]!!.jsonArray.single().jsonObject.str("status")
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); token?.let { bearerAuth(it) }; setBody(body) }

    private suspend fun HttpClient.patchJson(path: String, body: String, token: String): HttpResponse =
        patch(path) { contentType(ContentType.Application.Json); bearerAuth(token); setBody(body) }

    private suspend fun HttpClient.deleteJson(path: String, body: String, token: String): HttpResponse =
        delete(path) { contentType(ContentType.Application.Json); bearerAuth(token); setBody(body) }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    @Suppress("unused")
    private fun assertPresent(v: Any?) = assertNotNull(v)
}
