package com.taskly.server.features

import com.taskly.server.tasks.requireUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable

fun Route.featuresRoutes() {
    get("/v1/features") {
        val userId = requireUserId(call)
        val plan = call.request.queryParameters["plan"]
        val version = call.request.queryParameters["version"]
        val features = FeaturesService.getFeatures(userId, plan, version)
        call.respond(HttpStatusCode.OK, FeaturesResponse(features))
    }
}

@Serializable
data class FeaturesResponse(
    val flags: Map<String, Boolean>
)