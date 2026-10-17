package com.taskly.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun `health endpoint returns 200`() = testApplication {
        application {
            moduleForTest()
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}