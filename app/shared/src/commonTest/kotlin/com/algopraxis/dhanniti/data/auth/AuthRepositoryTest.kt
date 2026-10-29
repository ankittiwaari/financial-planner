package com.algopraxis.dhanniti.data.auth

import com.algopraxis.dhanniti.data.api.ApiException
import com.algopraxis.dhanniti.data.api.AuthApi
import com.algopraxis.dhanniti.data.api.createHttpClient
import com.algopraxis.dhanniti.data.db.UserDao
import com.algopraxis.dhanniti.data.db.UserEntity
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE_URL = "http://test.local/api/v1/"

private const val USER_JSON =
    """{"id":1,"name":"Ankit","email":"me@example.com","timezone":"Asia/Kolkata","currency":"INR"}"""

class AuthRepositoryTest {
    private val tokenStore = FakeTokenStore()
    private val userDao = FakeUserDao()
    private val requests = mutableListOf<HttpRequestData>()

    private fun repository(handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData): AuthRepository {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val client = createHttpClient(engine, BASE_URL, tokenStore)
        return AuthRepository(AuthApi(client), tokenStore, userDao, deviceName = "Test Phone")
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun loginStoresTokenAndCachesUser() = runTest {
        val repo = repository { json("""{"token":"1|abc","user":$USER_JSON}""") }

        repo.login("  me@example.com ", "secret")

        val request = requests.single()
        assertEquals("http://test.local/api/v1/auth/login", request.url.toString())
        val body = request.body.toByteArray().decodeToString()
        assertTrue(""""email":"me@example.com"""" in body, body)
        assertTrue(""""device_name":"Test Phone"""" in body, body)
        assertEquals("1|abc", tokenStore.token())
        assertEquals("Ankit", repo.currentUser.first()?.name)
        assertTrue(repo.isLoggedIn.first())
    }

    @Test
    fun loginValidationErrorIsMappedAndNothingIsStored() = runTest {
        val repo = repository {
            json(
                """{"message":"These credentials do not match our records.","errors":{"email":["These credentials do not match our records."]}}""",
                HttpStatusCode.UnprocessableEntity,
            )
        }

        val error = assertFailsWith<ApiException.Validation> { repo.login("me@example.com", "wrong") }

        assertEquals(listOf("These credentials do not match our records."), error.errors["email"])
        assertNull(tokenStore.token())
        assertNull(userDao.current.value)
    }

    @Test
    fun authenticatedRequestsSendBearerToken() = runTest {
        tokenStore.save("1|abc")
        val repo = repository { json("""{"user":$USER_JSON}""") }

        repo.refreshProfile()

        assertEquals("Bearer 1|abc", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun revokedTokenClearsLocalSession() = runTest {
        tokenStore.save("1|revoked")
        userDao.upsert(UserEntity(1, "Ankit", "me@example.com", "Asia/Kolkata", "INR"))
        val repo = repository { json("""{"message":"Unauthenticated."}""", HttpStatusCode.Unauthorized) }

        assertFailsWith<ApiException.Unauthorized> { repo.refreshProfile() }

        assertNull(tokenStore.token())
        assertNull(userDao.current.value)
    }

    @Test
    fun logoutClearsSessionEvenWhenServerIsDown() = runTest {
        tokenStore.save("1|abc")
        val repo = repository { error("connection refused") }

        repo.logout()

        assertNull(tokenStore.token())
    }
}

private class FakeTokenStore : TokenStore {
    private val state = MutableStateFlow<String?>(null)
    override val tokenFlow: Flow<String?> = state
    override suspend fun token() = state.value
    override suspend fun save(token: String) { state.value = token }
    override suspend fun clear() { state.value = null }
}

private class FakeUserDao : UserDao {
    val current = MutableStateFlow<UserEntity?>(null)
    override fun observeCurrent(): Flow<UserEntity?> = current
    override suspend fun upsert(user: UserEntity) { current.value = user }
    override suspend fun clear() { current.value = null }
}
