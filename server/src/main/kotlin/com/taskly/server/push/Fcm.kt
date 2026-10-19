package com.taskly.server.push

import com.taskly.server.db.Database
import com.taskly.server.db.PushDevicesTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Firebase Cloud Messaging HTTP v1 (AND-S4): a data message "sync" wakes the Android app in the
 * background so it pulls the change log. Authentication is the service-account OAuth flow
 * (RS256 JWT → access token), done with the JDK only.
 *
 * FCM_SERVER_KEY holds the service-account JSON (inline, base64, or a path to the file).
 */
class FcmSender(
    private val projectId: String,
    private val clientEmail: String,
    private val privateKey: PrivateKey,
    private val tokenUri: String,
    private val fcmBase: String = "https://fcm.googleapis.com",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val log = LoggerFactory.getLogger("FCM")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpires: Long = 0

    enum class Outcome { SENT, UNREGISTERED, FAILED }

    /** Signed assertion for the token endpoint (RFC 7523). */
    fun assertion(now: Long = clock()): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = enc.encodeToString(
            """{"iss":"$clientEmail","scope":"https://www.googleapis.com/auth/firebase.messaging","aud":"$tokenUri","iat":$now,"exp":${now + 3600}}""".toByteArray(),
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update("$header.$claims".toByteArray())
            sign()
        }
        return "$header.$claims.${enc.encodeToString(sig)}"
    }

    @Synchronized
    private fun token(): String {
        val now = clock()
        accessToken?.let { if (now < accessTokenExpires - 60) return it }
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion(now), Charsets.UTF_8)
        val resp = http.send(
            HttpRequest.newBuilder(URI(tokenUri))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(resp.statusCode() == 200) { "OAuth token request failed: ${resp.statusCode()}" }
        val body = json.parseToJsonElement(resp.body()).jsonObject
        val token = body["access_token"]!!.jsonPrimitive.content
        accessToken = token
        accessTokenExpires = now + (body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600)
        return token
    }

    /** Sends a data-only message (no notification UI) to one device token. */
    fun send(deviceToken: String, data: Map<String, String>): Outcome = try {
        val message = buildJsonObject {
            putJsonObject("message") {
                put("token", deviceToken)
                putJsonObject("data") { data.forEach { (k, v) -> put(k, v) } }
                putJsonObject("android") { put("priority", "high") }
            }
        }
        val resp = http.send(
            HttpRequest.newBuilder(URI("$fcmBase/v1/projects/$projectId/messages:send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer ${token()}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        when {
            resp.statusCode() in 200..299 -> Outcome.SENT
            // The app was uninstalled or the token rotated
            resp.statusCode() == 404 || resp.body().contains("UNREGISTERED") -> Outcome.UNREGISTERED
            else -> Outcome.FAILED.also { log.warn("FCM answered {}", resp.statusCode()) }
        }
    } catch (e: Exception) {
        log.warn("FCM send failed: {}", e.message)
        Outcome.FAILED
    }

    companion object {
        /** Parses a service-account JSON (inline, base64 or file path); null when [raw] is blank. */
        fun fromServiceAccount(raw: String, fcmBase: String = "https://fcm.googleapis.com"): FcmSender? {
            if (raw.isBlank()) return null
            val text = when {
                raw.trimStart().startsWith("{") -> raw
                File(raw).isFile -> File(raw).readText()
                else -> String(Base64.getDecoder().decode(raw.trim()))
            }
            val o = Json.parseToJsonElement(text).jsonObject
            val pem = o["private_key"]!!.jsonPrimitive.content
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "").replace("\n", "").trim()
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
            return FcmSender(
                projectId = o["project_id"]!!.jsonPrimitive.content,
                clientEmail = o["client_email"]!!.jsonPrimitive.content,
                privateKey = key,
                tokenUri = o["token_uri"]?.jsonPrimitive?.content ?: "https://oauth2.googleapis.com/token",
                fcmBase = fcmBase,
            )
        }
    }
}

/** Wakes a user's Android devices after their data changed. */
object DeviceNotifier {
    private val log = LoggerFactory.getLogger("DeviceNotifier")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var fcm: FcmSender? = null

    /** At most one wake-up per user in this window: a burst of edits needs one pull. */
    @Volatile
    var throttleMillis: Long = 5_000
    private val lastSent = ConcurrentHashMap<UUID, Long>()

    fun init(serviceAccount: String) {
        fcm = runCatching { FcmSender.fromServiceAccount(serviceAccount) }
            .onFailure { log.error("FCM_SERVER_KEY is not a valid service-account JSON; Android background wake-ups are off", it) }
            .getOrNull()
    }

    /** Called after a transaction committed changes for [userIds]. Never blocks the request. */
    fun changed(userIds: Collection<UUID>) {
        val sender = fcm ?: return
        val now = System.currentTimeMillis()
        val due = userIds.toSet().filter { u ->
            val prev = lastSent[u]
            (prev == null || now - prev >= throttleMillis).also { if (it) lastSent[u] = now }
        }
        if (due.isEmpty()) return
        scope.launch { due.forEach { wake(sender, it) } }
    }

    /** Sends the wake-up synchronously (tests and the background job). Returns messages sent. */
    fun wake(sender: FcmSender, userId: UUID): Int {
        val devices = Database.ctx().selectFrom(PushDevicesTable.TABLE).where(PushDevicesTable.USER_ID.eq(userId)).fetch()
        var sent = 0
        for (d in devices) {
            when (sender.send(d.get(PushDevicesTable.TOKEN), mapOf("type" to "sync"))) {
                FcmSender.Outcome.SENT -> sent++
                FcmSender.Outcome.UNREGISTERED -> Database.ctx().deleteFrom(PushDevicesTable.TABLE)
                    .where(PushDevicesTable.ID.eq(d.get(PushDevicesTable.ID))).execute()
                FcmSender.Outcome.FAILED -> Unit
            }
        }
        return sent
    }
}
