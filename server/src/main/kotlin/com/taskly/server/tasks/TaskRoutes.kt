package com.taskly.server.tasks

import com.taskly.server.db.Database
import com.taskly.server.db.ListsTable
import com.taskly.server.dto.*
import com.taskly.server.errors.ForbiddenException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import org.jooq.impl.DSL
import java.util.*

fun Route.taskRoutes() {
    get("/v1/tasks") {
        val userId = requireUserId(call)
        val listId = call.request.queryParameters["list_id"]
        val q = call.request.queryParameters["q"]
        val cursor = call.request.queryParameters["cursor"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

        val (tasks, nextCursor) = TaskService.list(userId, listId, q, cursor, limit.coerceIn(1, 200))
        call.respond(HttpStatusCode.OK, PaginatedResponse(data = tasks, cursor = nextCursor))
    }

    post("/v1/tasks") {
        val userId = requireUserId(call)
        val request = call.receive<CreateTaskRequest>()

        // SEC-1: ownership check on entity create — reject foreign list_id
        if (request.listId != null) {
            val ownerId = UUID.fromString(userId)
            val count = Database.ctx()
                .select(DSL.count())
                .from(ListsTable.TABLE)
                .where(ListsTable.ID.eq(UUID.fromString(request.listId)))
                .and(ListsTable.OWNER_ID.eq(ownerId))
                .fetchOneInto(Long::class.java) ?: 0L
            if (count == 0L) {
                throw ForbiddenException("List not found or access denied")
            }
        }

        // SEC-2: reference check — parent_id within same scope
        // (parent_id is not part of CreateTaskRequest yet; checked at sync level)

        val task = TaskService.create(userId, request)
        call.respond(HttpStatusCode.Created, task)
    }

    get("/v1/tasks/{id}") {
        val userId = requireUserId(call)
        val taskId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task id"))
        val task = TaskService.get(userId, taskId)
        if (task != null) {
            call.respond(HttpStatusCode.OK, task)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Task not found"))
        }
    }

    // Change history of the task card (WEB-F3): task, its notes and attachments, newest first
    get("/v1/tasks/{id}/history") {
        val userId = requireUserId(call)
        val taskId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task id"))
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
        call.respond(HttpStatusCode.OK, TaskChildren.history(userId, taskId, limit))
    }

    // Notes of one task (clients re-read them after a conflict, AND-S5)
    get("/v1/tasks/{id}/notes") {
        val userId = requireUserId(call)
        val taskId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task id"))
        call.respond(HttpStatusCode.OK, TaskChildren.notesOfTask(userId, taskId))
    }

    patch("/v1/tasks/{id}") {
        val userId = requireUserId(call)
        val taskId = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task id"))
        val request = call.receive<UpdateTaskRequest>()
        call.respond(HttpStatusCode.OK, TaskService.update(userId, taskId, request))
    }

    delete("/v1/tasks/{id}") {
        val userId = requireUserId(call)
        val taskId = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task id"))
        TaskService.delete(userId, taskId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/tasks/{id}/complete") {
        val userId = requireUserId(call)
        val taskId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task id"))
        call.respond(HttpStatusCode.OK, TaskService.complete(userId, taskId))
    }
}