package com.taskly.server.account

import com.taskly.server.auth.AuthService
import com.taskly.server.db.Database
import com.taskly.server.db.PushSubscriptionsTable
import com.taskly.server.errors.NotFoundException
import com.taskly.server.push.PushEndpoints
import com.taskly.server.push.PushOutcome
import com.taskly.server.push.PushSubscriptionKeys
import com.taskly.server.push.WebPush
import com.taskly.server.push.WebPushCrypto
import com.taskly.server.tasks.requireUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class DeleteAccountRequest(val password: String? = null, val confirmEmail: String? = null, val totpCode: String? = null)

@Serializable
data class PushKeys(val p256dh: String, val auth: String)

@Serializable
data class PushSubscriptionRequest(val endpoint: String, val keys: PushKeys)

@Serializable
data class PushUnsubscribeRequest(val endpoint: String)

@Serializable
data class VapidKeyResponse(val publicKey: String)

@Serializable
data class PushTestResponse(val sent: Int)

fun Route.accountRoutes() {
    // ── Settings (WEB-F9) ──
    get("/v1/account/settings") {
        call.respond(HttpStatusCode.OK, AccountService.settings(requireUserId(call)))
    }
    patch("/v1/account/settings") {
        val userId = requireUserId(call)
        call.respond(HttpStatusCode.OK, AccountService.update(userId, call.receive<SettingsPatch>()))
    }

    // ── Data export and account deletion ──
    get("/v1/account/export") {
        val userId = requireUserId(call)
        call.response.headers.append("Content-Disposition", "attachment; filename=\"taskly-export.json\"")
        call.respond(HttpStatusCode.OK, AccountService.export(userId))
    }
    delete("/v1/account") {
        val userId = requireUserId(call)
        val r = call.receive<DeleteAccountRequest>()
        AuthService.deleteAccount(userId, r.password, r.confirmEmail, r.totpCode)
        call.respond(HttpStatusCode.NoContent)
    }

    // ── Web Push subscriptions (WEB-F8) ──
    get("/v1/push/vapid-key") {
        val key = WebPush.publicKey ?: throw NotFoundException("Web Push is not configured on this server")
        call.respond(HttpStatusCode.OK, VapidKeyResponse(key))
    }
    post("/v1/push/subscriptions") {
        val userId = UUID.fromString(requireUserId(call))
        val r = call.receive<PushSubscriptionRequest>()
        require(PushEndpoints.isAllowed(r.endpoint)) { "Unsupported push service" }
        val p256dh = runCatching { WebPushCrypto.unb64url(r.keys.p256dh) }.getOrNull()
        require(p256dh != null && runCatching { WebPushCrypto.decodePublic(p256dh) }.isSuccess) { "keys.p256dh is not a P-256 public key" }
        val auth = runCatching { WebPushCrypto.unb64url(r.keys.auth) }.getOrNull()
        require(auth != null && auth.size == 16) { "keys.auth must be 16 bytes" }
        // One browser = one endpoint; re-subscribing (or signing in as someone else) moves it
        Database.ctx().insertInto(PushSubscriptionsTable.TABLE)
            .set(PushSubscriptionsTable.USER_ID, userId)
            .set(PushSubscriptionsTable.ENDPOINT, r.endpoint)
            .set(PushSubscriptionsTable.P256DH, r.keys.p256dh)
            .set(PushSubscriptionsTable.AUTH, r.keys.auth)
            .onConflict(PushSubscriptionsTable.ENDPOINT)
            .doUpdate()
            .set(PushSubscriptionsTable.USER_ID, userId)
            .set(PushSubscriptionsTable.P256DH, r.keys.p256dh)
            .set(PushSubscriptionsTable.AUTH, r.keys.auth)
            .execute()
        call.respond(HttpStatusCode.Created, buildJsonObject { put("status", "subscribed") })
    }
    delete("/v1/push/subscriptions") {
        val userId = UUID.fromString(requireUserId(call))
        val r = call.receive<PushUnsubscribeRequest>()
        Database.ctx().deleteFrom(PushSubscriptionsTable.TABLE)
            .where(PushSubscriptionsTable.USER_ID.eq(userId), PushSubscriptionsTable.ENDPOINT.eq(r.endpoint))
            .execute()
        call.respond(HttpStatusCode.NoContent)
    }
    /** "Send a test notification" in the settings. */
    post("/v1/push/test") {
        val userId = UUID.fromString(requireUserId(call))
        val sender = WebPush.sender ?: throw NotFoundException("Web Push is not configured on this server")
        val subs = Database.ctx().selectFrom(PushSubscriptionsTable.TABLE).where(PushSubscriptionsTable.USER_ID.eq(userId)).fetch()
        val payload = buildJsonObject {
            put("type", "test")
            put("title", "Taskly")
            put("body", "Уведомления работают")
        }.toString()
        var sent = 0
        for (s in subs) {
            val outcome = sender.send(PushSubscriptionKeys(s.get(PushSubscriptionsTable.ENDPOINT), s.get(PushSubscriptionsTable.P256DH), s.get(PushSubscriptionsTable.AUTH)), payload)
            if (outcome == PushOutcome.SENT) sent++
            if (outcome == PushOutcome.GONE) {
                Database.ctx().deleteFrom(PushSubscriptionsTable.TABLE).where(PushSubscriptionsTable.ID.eq(s.get(PushSubscriptionsTable.ID))).execute()
            }
        }
        call.respond(HttpStatusCode.OK, PushTestResponse(sent))
    }
}
