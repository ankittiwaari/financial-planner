package com.algopraxis.dhanniti.data.api

import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.domain.LendingStatus
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.personKey
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Checks the app reads and writes the milestone 3 shapes in backend/openapi.yaml. */
class LendingApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(body: String, status: HttpStatusCode = HttpStatusCode.OK): LendingApi {
        val engine = MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tokens = object : TokenStore {
            override val tokenFlow = MutableStateFlow<String?>("1|t")
            override suspend fun token() = "1|t"
            override suspend fun save(token: String) = Unit
            override suspend fun clear() = Unit
        }
        return LendingApi(createHttpClient(engine, "http://test.local/api/v1/", tokens))
    }

    private val lendingJson =
        """{"id":5,"person_name":"Ravi","contact":null,"amount":2000000,"repaid":500000,"outstanding":1500000,"lent_on":"2026-10-01","expected_by":"2026-09-30","notes":null,"account_id":3,"transaction_id":40,"status":"partially_repaid","closed_on":null,"is_overdue":true,"repayments":[{"id":9,"amount":500000,"received_on":"2026-10-29","account_id":null,"transaction_id":null}]}"""

    @Test
    fun readsLendingWithRepayments() = runTest {
        val lending = api("""{"data":[$lendingJson]}""").lendings().single()

        assertEquals(LendingStatus.PARTIALLY_REPAID, lending.status)
        assertEquals(Money(1_500_000), lending.outstanding)
        assertTrue(lending.isOutstanding)
        assertTrue(lending.isOverdue)
        assertEquals(LocalDate(2026, 10, 29), lending.repayments.single().receivedOn)
    }

    @Test
    fun readsPerPersonSummary() = runTest {
        val summary = api("""{"data":{"total_outstanding":1500000,"overdue_total":1500000,"people_owing":1,"people":[{"name":"Ravi","contact":null,"lent":2000000,"repaid":500000,"written_off":0,"outstanding":1500000,"open_count":1,"overdue":true,"next_expected_by":"2026-09-30","last_lent_on":"2026-10-01","lending_ids":[5]}]}}""")
            .summary()

        assertEquals(Money(1_500_000), summary.totalOutstanding)
        assertEquals(1, summary.peopleOwing)
        assertEquals(listOf(5L), summary.people.single().lendingIds)
        assertTrue(summary.people.single().overdue)
    }

    @Test
    fun repaysWithoutAnAccountByOmittingIt() = runTest {
        api("""{"data":$lendingJson}""", HttpStatusCode.Created)
            .repay(5, RepaymentInput(Money(500_000), LocalDate(2026, 10, 29)))

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://test.local/api/v1/lendings/5/repayments", request.url.toString())
        val body = request.body.toByteArray().decodeToString()
        assertEquals("""{"amount":500000,"received_on":"2026-10-29"}""", body)
        assertFalse(body.contains("account_id"))
    }

    @Test
    fun matchesPeopleLikeTheServer() {
        assertEquals("ravi kumar", personKey("  Ravi   Kumar "))
        assertEquals(personKey("RAVI KUMAR"), personKey("ravi kumar"))
    }
}
