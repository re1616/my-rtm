package com.taskly.server.lists

import com.taskly.server.dto.*
import com.taskly.server.tasks.requireUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*

fun Route.listRoutes() {
    get("/v1/lists") {
        val userId = requireUserId(call)
        val lists = ListService.list(userId)
        call.respond(HttpStatusCode.OK, lists)
    }

    post("/v1/lists") {
        val userId = requireUserId(call)
        val request = call.receive<CreateListRequest>()
        val list = ListService.create(userId, request)
        call.respond(HttpStatusCode.Created, list)
    }

    get("/v1/lists/{id}") {
        val userId = requireUserId(call)
        val listId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing list id"))
        val list = ListService.get(userId, listId)
        if (list != null) {
            call.respond(HttpStatusCode.OK, list)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("List not found"))
        }
    }

    patch("/v1/lists/{id}") {
        val userId = requireUserId(call)
        val listId = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing list id"))
        val request = call.receive<UpdateListRequest>()
        try {
            val list = ListService.update(userId, listId, request)
            call.respond(HttpStatusCode.OK, list)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message!!))
        }
    }

    delete("/v1/lists/{id}") {
        val userId = requireUserId(call)
        val listId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing list id"))
        try {
            ListService.delete(userId, listId)
            call.respond(HttpStatusCode.NoContent)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message!!))
        }
    }
}