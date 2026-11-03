package com.algopraxis.dhanniti.data.sync

import com.algopraxis.dhanniti.data.api.ApiJson
import com.algopraxis.dhanniti.data.api.PayDueInput
import com.algopraxis.dhanniti.data.api.TransactionInput
import com.algopraxis.dhanniti.domain.Cycle
import com.algopraxis.dhanniti.domain.CycleIncome
import com.algopraxis.dhanniti.domain.CycleMode
import com.algopraxis.dhanniti.domain.Dashboard
import com.algopraxis.dhanniti.domain.DashboardReceivables
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.ReservedForDues
import com.algopraxis.dhanniti.domain.Transaction
import com.algopraxis.dhanniti.domain.withPending
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineTest {
    private val dashboard = Dashboard(
        cycle = Cycle(LocalDate(2026, 10, 30), LocalDate(2026, 11, 29), CycleMode.PAYDAY, daysTotal = 31, daysLeft = 27),
        income = CycleIncome(Money(12_000_000), Money(12_000_000), Money.ZERO, Money(12_000_000)),
        reservedForDues = ReservedForDues(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO),
        spentSoFar = Money.ZERO,
        lentOut = Money.ZERO,
        purchasingPower = Money(12_000_000),
        perDayLeft = Money(444_444),
        receivables = DashboardReceivables(Money.ZERO, 0, Money.ZERO),
    )

    private fun transaction(direction: Direction, amount: Long, on: LocalDate = LocalDate(2026, 11, 3)) =
        Transaction(id = -1, accountId = 1, direction = direction, amount = Money(amount), occurredOn = on, pendingSync = true)

    @Test
    fun offlineSpendingLowersPurchasingPowerUntilTheServerAnswers() {
        val after = dashboard.withPending(transaction(Direction.DEBIT, 270_000))

        assertEquals(Money(270_000), after.spentSoFar)
        assertEquals(Money(11_730_000), after.purchasingPower)
        assertEquals(Money(434_444), after.perDayLeft) // 11,730,000 / 27
    }

    @Test
    fun offlineIncomeRaisesItAndOutsideTheCycleChangesNothing() {
        val income = dashboard.withPending(transaction(Direction.CREDIT, 500_000))
        assertEquals(Money(500_000), income.income.other)
        assertEquals(Money(12_500_000), income.purchasingPower)

        assertEquals(dashboard, dashboard.withPending(transaction(Direction.DEBIT, 100, on = LocalDate(2026, 10, 29))))
    }

    @Test
    fun neverShowsANegativeDailyAllowance() {
        assertEquals(Money.ZERO, dashboard.withPending(transaction(Direction.DEBIT, 13_000_000)).perDayLeft)
    }

    @Test
    fun pendingChangesSurviveBeingStored() {
        val changes = listOf<PendingChange>(
            PendingChange.CreateTransaction(
                "8f14e45f-ceea-4b1b-9a6e-1d1d4f3c2a01",
                TransactionInput(3, 7, Direction.DEBIT, Money(45_000), LocalDate(2026, 11, 3), "Chai", "8f14e45f-ceea-4b1b-9a6e-1d1d4f3c2a01"),
                "Expense ₹450 · Chai on 3 Nov 2026",
            ),
            PendingChange.PayDue(81, PayDueInput(paidOn = LocalDate(2026, 11, 3)), "Paid Home loan EMI ₹31,000"),
        )

        changes.forEach { change ->
            val json = ApiJson.encodeToString(PendingChange.serializer(), change)
            assertEquals(change, ApiJson.decodeFromString(PendingChange.serializer(), json))
        }
    }

    @Test
    fun theTransactionSentForAnOfflineEntryCarriesItsReference() {
        val json = ApiJson.encodeToString(
            TransactionInput.serializer(),
            TransactionInput(3, null, Direction.DEBIT, Money(45_000), LocalDate(2026, 11, 3), null, "ref-1"),
        )
        assertTrue(json.contains("\"client_ref\":\"ref-1\""))
        assertFalse(ApiJson.encodeToString(TransactionInput.serializer(), TransactionInput(3, null, Direction.DEBIT, Money(1), LocalDate(2026, 11, 3), null)).contains("client_ref"))
    }
}
