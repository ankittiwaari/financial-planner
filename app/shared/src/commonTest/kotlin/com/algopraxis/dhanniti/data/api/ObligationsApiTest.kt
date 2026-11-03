package com.algopraxis.dhanniti.data.api

import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.domain.DueSource
import com.algopraxis.dhanniti.domain.DueStatus
import com.algopraxis.dhanniti.domain.EstimateMode
import com.algopraxis.dhanniti.domain.LoanType
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.StatementStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks the app reads and writes the milestone 2 shapes in backend/openapi.yaml. */
class ObligationsApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(body: String, status: HttpStatusCode = HttpStatusCode.OK): ObligationsApi {
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
        return ObligationsApi(createHttpClient(engine, "http://test.local/api/v1/", tokens))
    }

    private suspend fun lastBody() = requests.last().body.toByteArray().decodeToString()

    @Test
    fun readsLoanWithProgress() = runTest {
        val loan = api("""{"data":[{"id":4,"name":"Car loan","lender":null,"type":"car","principal":100000000,"interest_rate":12,"tenure_months":12,"emi_amount":null,"emi":8884900,"first_emi_date":"2026-08-05","last_emi_date":"2027-07-05","account_id":3,"auto_debit":true,"status":"active","closed_on":null,"outstanding":76107965,"emis_paid":3,"emis_left":9,"next_emi_date":"2026-11-05","total_interest":6618479}]}""")
            .loans().single()

        assertEquals(LoanType.CAR, loan.type)
        assertEquals(12.0, loan.interestRate)
        assertNull(loan.emiAmount)
        assertEquals(Money(8_884_900), loan.emi)
        assertEquals(Money(76_107_965), loan.outstanding)
        assertEquals(LocalDate(2026, 11, 5), loan.nextEmiDate)
        assertTrue(loan.isActive)
    }

    @Test
    fun writesLoanWithDecimalRateAndOmitsBlankEmi() = runTest {
        api("""{"data":{"id":4,"name":"Home","type":"home","principal":1,"interest_rate":8.5,"tenure_months":240,"emi":1,"first_emi_date":"2026-12-05","account_id":3,"auto_debit":true,"status":"active","outstanding":1,"emis_paid":0,"emis_left":240,"total_interest":0}}""", HttpStatusCode.Created)
            .createLoan(LoanInput("Home", null, LoanType.HOME, Money(500_000_000), 8.5, 240, null, LocalDate(2026, 12, 5), 3, true))

        assertEquals(
            """{"name":"Home","type":"home","principal":500000000,"interest_rate":8.5,"tenure_months":240,"first_emi_date":"2026-12-05","account_id":3,"auto_debit":true}""",
            lastBody(),
        )
    }

    @Test
    fun readsCardWithEstimatedStatement() = runTest {
        val card = api("""{"data":[{"id":2,"nickname":"ICICI Amazon Pay","last4":"4321","issuer":null,"credit_limit":null,"statement_day":28,"due_day":17,"pay_from_account_id":3,"estimate_mode":"avg_3","fixed_estimate":null,"latest_statement":{"id":9,"credit_card_id":2,"period":"2026-10","statement_date":"2026-10-28","due_date":"2026-11-17","total_due":1200000,"min_due":null,"amount_paid":0,"status":"estimated"}}]}""")
            .creditCards().single()

        assertEquals("ICICI Amazon Pay ••4321", card.label)
        assertEquals(EstimateMode.AVERAGE_3, card.estimateMode)
        assertEquals(StatementStatus.ESTIMATED, card.latestStatement?.status)
        assertEquals(LocalDate(2026, 11, 17), card.latestStatement?.dueDate)
    }

    @Test
    fun readsDuesIncludingOverdueAndDeletedSources() = runTest {
        val dues = api("""{"data":[
            {"id":1,"source_type":"loan","source_id":4,"period":"2026-11","title":"Car loan EMI","amount":8884900,"due_date":"2026-11-05","status":"overdue","paid_on":null,"paid_amount":null,"transaction_id":null,"account_id":3,"auto_debit":true,"is_estimate":false,"min_due":null},
            {"id":2,"source_type":"card_statement","source_id":9,"period":"2026-10","title":"ICICI bill","amount":0,"due_date":"2026-11-17","status":"upcoming","paid_on":null,"paid_amount":null,"transaction_id":null,"account_id":3,"auto_debit":false,"is_estimate":true,"min_due":null},
            {"id":3,"source_type":"recurring_payment","source_id":5,"period":"2026-10","title":"Rent","amount":2500000,"due_date":"2026-10-01","status":"paid","paid_on":"2026-10-01","paid_amount":2500000,"transaction_id":77,"account_id":null,"auto_debit":false,"is_estimate":false,"min_due":null}
        ]}""").dues()

        assertEquals(listOf(DueSource.LOAN, DueSource.CARD, DueSource.RECURRING), dues.map { it.sourceType })
        assertEquals(listOf(DueStatus.OVERDUE, DueStatus.UPCOMING, DueStatus.PAID), dues.map { it.status })
        assertEquals(listOf(true, true, false), dues.map { it.isOpen })
        assertTrue(dues[1].isEstimate)
        assertNull(dues[2].accountId)
        assertEquals(77L, dues[2].transactionId)
    }

    @Test
    fun paysWithOnlyTheFieldsGiven() = runTest {
        val body = """{"data":{"id":1,"source_type":"loan","source_id":4,"period":"2026-11","title":"EMI","amount":100,"due_date":"2026-11-05","status":"paid","paid_on":"2026-11-04","paid_amount":100,"transaction_id":8,"account_id":3,"auto_debit":true,"is_estimate":false,"min_due":null}}"""
        api(body).payDue(1, PayDueInput(amount = Money(100), paidOn = LocalDate(2026, 11, 4)))

        assertEquals("http://test.local/api/v1/dues/1/pay", requests.single().url.toString())
        assertEquals("""{"amount":100,"paid_on":"2026-11-04"}""", lastBody())
    }
}
