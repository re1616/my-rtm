package com.taskly.server.sync

import com.taskly.server.dto.*
import com.taskly.server.tasks.requireUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*

fun Route.syncRoutes() {
    post("/v1/sync") {
        val userId = requireUserId(call)
        val request = call.receive<SyncRequest>()
        val response = SyncService.processOperations(userId, request.ops)
        call.respond(HttpStatusCode.OK, response)
    }

    get("/v1/sync/changes") {
        val userId = requireUserId(call)
        val scope = call.request.queryParameters["scope"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing scope parameter"))
        val after = call.request.queryParameters["after"]?.toLongOrNull()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

        val (entries, lastSeq) = SyncService.getChanges(userId, scope, after, limit.coerceIn(1, 500))
        call.respond(
            HttpStatusCode.OK,
            SyncChangesResponse(
                changes = entries,
                cursor = lastSeq?.toString()
            )
        )
    }
}

@kotlinx.serialization.Serializable
data class SyncChangesResponse(
    val changes: List<com.taskly.server.sync.SyncService.SyncChangeEntry>,
    val cursor: String? = null
)