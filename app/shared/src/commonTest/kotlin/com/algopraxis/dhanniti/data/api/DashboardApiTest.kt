package com.algopraxis.dhanniti.data.api

import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.domain.CycleMode
import com.algopraxis.dhanniti.domain.Money
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the app reads the milestone 4 dashboard in backend/openapi.yaml. */
class DashboardApiTest {
    @Test
    fun readsDashboardIncludingNegativePowerAndNextDues() = runTest {
        val engine = MockEngine {
            respond(
                """{"data":{
                    "cycle":{"start":"2026-10-30","end":"2026-11-29","mode":"payday","anchor_income_source_id":1,"days_total":31,"days_left":27},
                    "income":{"expected":12000000,"credited":0,"other":900000,"total":12900000,"sources":[{"income_source_id":1,"name":"Salary","period":"2026-10","pay_date":"2026-10-30","expected":12000000,"credited":null,"is_variable":false,"awaiting":true}]},
                    "reserved_for_dues":{"total":5874800,"emis":3100000,"cards":1895000,"recurring":879800,"overdue":64900,"paid":750000,"open":5124800},
                    "spent_so_far":330000,"lent_out":1000000,"purchasing_power":-5000,"per_day_left":0,
                    "receivables":{"total_lent_outstanding":600000,"people":1,"overdue":0},
                    "next_dues":[{"id":1,"source_type":"loan","source_id":4,"period":"2026-11","title":"Home loan EMI","amount":3100000,"due_date":"2026-11-05","status":"upcoming","paid_on":null,"paid_amount":null,"transaction_id":null,"account_id":3,"auto_debit":true,"is_estimate":false,"min_due":null}],
                    "alerts":[{"type":"confirm_income","message":"Log Salary for Oct 2026 (payday was 30 Oct)"}]
                }}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val tokens = object : TokenStore {
            override val tokenFlow = MutableStateFlow<String?>("1|t")
            override suspend fun token() = "1|t"
            override suspend fun save(token: String) = Unit
            override suspend fun clear() = Unit
        }

        val dashboard = LedgerApi(createHttpClient(engine, "http://test.local/api/v1/", tokens)).dashboard()

        assertEquals(CycleMode.PAYDAY, dashboard.cycle.mode)
        assertEquals(LocalDate(2026, 11, 29), dashboard.cycle.end)
        assertEquals(27, dashboard.cycle.daysLeft)
        assertTrue(dashboard.purchasingPower.isNegative)
        assertEquals(Money(5_874_800), dashboard.reservedForDues.total)
        assertTrue(dashboard.income.sources.single().awaiting)
        assertEquals("Home loan EMI", dashboard.nextDues.single().title)
        assertEquals("confirm_income", dashboard.alerts.single().type)
    }
}
