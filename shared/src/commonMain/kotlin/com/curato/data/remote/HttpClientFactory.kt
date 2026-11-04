package com.curato.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * One configured client for the whole app.
 *
 * It takes an [HttpClientEngine], not an [HttpClient]. That distinction is load-bearing:
 * a platform module supplying `HttpClient` while this function also produces one gives
 * the container a definition whose own dependency is its own type, and resolving it
 * recurses until the stack runs out. The engine is the platform's half; the
 * configuration is the shared half.
 */
fun createHttpClient(
    engine: HttpClientEngine,
    /** Debug builds log the exchange; release builds must not. */
    logRequests: Boolean = false,
): HttpClient = HttpClient(engine) {
    if (logRequests) {
        install(Logging) { level = LogLevel.INFO }
    }
    install(ContentNegotiation) {
        json(
            Json {
                // A field the backend adds must not break a client that predates it.
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
    defaultRequest {
        contentType(ContentType.Application.Json)
    }
}
