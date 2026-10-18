package com.taskly.server.tasks

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import com.taskly.server.dto.*
import com.taskly.server.tasks.requireUserId

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