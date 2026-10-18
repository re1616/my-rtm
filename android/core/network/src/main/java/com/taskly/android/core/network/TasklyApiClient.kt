package com.taskly.android.core.network

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object TasklyApiClient {
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun create(baseUrl: String = DEFAULT_BASE_URL): HttpClient {
        return HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            install(WebSockets)
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
            defaultRequest {
                url(baseUrl)
            }
        }
    }
}