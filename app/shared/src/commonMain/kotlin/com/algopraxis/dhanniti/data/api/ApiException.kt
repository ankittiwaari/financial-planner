package com.algopraxis.dhanniti.data.api

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

sealed class ApiException(message: String) : Exception(message) {
    class Unauthorized : ApiException("Your session has expired. Please log in again.")

    /** Laravel validation failure (HTTP 422). [errors] maps field name to messages. */
    class Validation(message: String, val errors: Map<String, List<String>>) : ApiException(message)

    class Http(val status: Int, message: String) : ApiException(message)
}

/** Laravel's standard error body: `{"message": "...", "errors": {"field": ["..."]}}`. */
@Serializable
internal data class ErrorBody(
    val message: String? = null,
    val errors: Map<String, List<String>> = emptyMap(),
)

internal suspend fun HttpResponse.ensureSuccess() {
    if (status.isSuccess()) return
    val error = runCatching { body<ErrorBody>() }.getOrNull()
    throw when (status) {
        HttpStatusCode.Unauthorized -> ApiException.Unauthorized()
        HttpStatusCode.UnprocessableEntity ->
            ApiException.Validation(error?.message ?: "Please check the details you entered.", error?.errors.orEmpty())
        else -> ApiException.Http(status.value, error?.message ?: "Server error (${status.value}).")
    }
}

/** What to show a person for a failed call: the first validation message, the server's message, or a network hint. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException.Validation -> errors.values.flatten().firstOrNull() ?: message.orEmpty()
    is ApiException -> message.orEmpty()
    else -> "Can't reach the server. Check your connection and try again."
}

internal suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
    ensureSuccess()
    return body()
}
