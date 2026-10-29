package com.algopraxis.dhanniti.data.api

import com.algopraxis.dhanniti.data.auth.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.plugins.logging.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * @param baseUrl must end with a slash, e.g. `http://10.0.2.2:8000/api/v1/`, so relative paths like `auth/login` resolve under it.
 */
fun createHttpClient(
    engine: HttpClientEngine,
    baseUrl: String,
    tokenStore: TokenStore,
    enableLogging: Boolean = false,
): HttpClient = HttpClient(engine) {
    // Status codes are mapped to ApiException in ensureSuccess(), so the client itself never throws on 4xx/5xx.
    expectSuccess = false

    install(ContentNegotiation) { json(ApiJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
    install(bearerToken(tokenStore))
    if (enableLogging) {
        install(Logging) {
            logger = Logger.SIMPLE
            level = LogLevel.INFO
        }
    }
    defaultRequest {
        url(baseUrl)
        headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
    }
}

/** Reads the token on every request, so logging in or out takes effect without rebuilding the client. */
private fun bearerToken(tokenStore: TokenStore) = createClientPlugin("BearerToken") {
    onRequest { request, _ ->
        val token = tokenStore.token()
        if (token != null && !request.headers.contains(HttpHeaders.Authorization)) {
            request.headers.append(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

internal fun io.ktor.client.request.HttpRequestBuilder.jsonBody() = contentType(ContentType.Application.Json)
