package com.taskly.server.tags

import com.taskly.server.dto.*
import com.taskly.server.tasks.requireUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*

fun Route.tagRoutes() {
    get("/v1/tags") {
        val userId = requireUserId(call)
        val tags = TagService.list(userId)
        call.respond(HttpStatusCode.OK, tags)
    }

    post("/v1/tags") {
        val userId = requireUserId(call)
        val request = call.receive<CreateTagRequest>()
        try {
            val tag = TagService.create(userId, request)
            call.respond(HttpStatusCode.Created, tag)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(error = e.message ?: "Tag creation failed"))
        }
    }

    delete("/v1/tags/{id}") {
        val userId = requireUserId(call)
        val tagId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing tag id"))
        try {
            TagService.delete(userId, tagId)
            call.respond(HttpStatusCode.NoContent)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message!!))
        }
    }
}