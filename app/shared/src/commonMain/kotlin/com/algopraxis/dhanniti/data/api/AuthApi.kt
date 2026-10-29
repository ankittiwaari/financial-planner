package com.algopraxis.dhanniti.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Endpoints documented in backend/openapi.yaml. */
class AuthApi(private val client: HttpClient) {

    suspend fun login(email: String, password: String, deviceName: String): LoginResponse =
        client.post("auth/login") {
            jsonBody()
            setBody(LoginRequest(email, password, deviceName))
        }.bodyOrThrow()

    suspend fun me(): UserDto = client.get("me").bodyOrThrow<UserEnvelope>().user

    suspend fun logout() {
        client.post("auth/logout").ensureSuccess()
    }
}

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserDto,
)

@Serializable
data class UserEnvelope(val user: UserDto)

@Serializable
data class UserDto(
    val id: Long,
    val name: String,
    val email: String,
    val timezone: String,
    val currency: String,
)
