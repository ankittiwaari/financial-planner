package com.algopraxis.dhanniti.data.api

import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.domain.AccountType
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.PayRule
import com.algopraxis.dhanniti.domain.WeekendShift
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Checks the app reads and writes the shapes in backend/openapi.yaml. */
class LedgerApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String): LedgerApi {
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
        return LedgerApi(createHttpClient(engine, "http://test.local/api/v1/", tokens))
    }

    @Test
    fun readsAccountsWithPaise() = runTest {
        val accounts = api(body = """{"data":[{"id":3,"name":"HDFC","type":"bank","opening_balance":-150050,"balance":12000000,"is_primary":true,"archived":false}]}""")
            .accounts()

        val account = accounts.single()
        assertEquals(AccountType.BANK, account.type)
        assertEquals(Money(-150_050), account.openingBalance)
        assertEquals(Money(12_000_000), account.balance)
        assertTrue(account.isPrimary)
    }

    @Test
    fun writesTransactionInSnakeCaseAndOmitsNulls() = runTest {
        api(
            HttpStatusCode.Created,
            """{"data":{"id":9,"account_id":3,"category_id":null,"direction":"debit","amount":45050,"occurred_on":"2026-10-28","description":null,"source_type":null,"source_id":null,"period":null,"is_auto":false}}""",
        ).createTransaction(
            TransactionInput(3, categoryId = null, Direction.DEBIT, Money(45_050), LocalDate(2026, 10, 28), description = null),
        )

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://test.local/api/v1/transactions", request.url.toString())
        val body = request.body.toByteArray().decodeToString()
        assertEquals("""{"account_id":3,"direction":"debit","amount":45050,"occurred_on":"2026-10-28"}""", body)
    }

    @Test
    fun readsIncomeSourceSchedule() = runTest {
        val source = api(body = """{"data":[{"id":1,"name":"Salary","account_id":3,"amount":12000000,"is_variable":false,"pay_rule":"last_working_day","pay_day":null,"weekend_shift":"before","auto_credit":true,"active_from":"2026-10-01","active_to":null,"next_pay_date":"2026-10-30","last_credited_period":"2026-09"}]}""")
            .incomeSources().single()

        assertEquals(PayRule.LAST_WORKING_DAY, source.payRule)
        assertEquals(WeekendShift.BEFORE, source.weekendShift)
        assertEquals(LocalDate(2026, 10, 30), source.nextPayDate)
        assertEquals("2026-09", source.lastCreditedPeriod)
    }

    @Test
    fun readsCursorPage() = runTest {
        val page = api(body = """{"data":[],"meta":{"next_cursor":"abc"}}""").transactions(direction = Direction.CREDIT)

        assertEquals("abc", page.meta.nextCursor)
        assertEquals("credit", requests.single().url.parameters["direction"])
    }

    @Test
    fun duplicateCreditIsAnHttpError() = runTest {
        val error = assertFailsWith<ApiException.Http> {
            api(HttpStatusCode.Conflict, """{"message":"Salary is already logged for Oct 2026."}""")
                .logIncomeCredit(1, IncomeCreditInput(period = "2026-10"))
        }
        assertEquals(409, error.status)
        assertEquals("Salary is already logged for Oct 2026.", error.userMessage())
        assertFalse(requests.single().body.toByteArray().decodeToString().contains("amount"))
    }
}
