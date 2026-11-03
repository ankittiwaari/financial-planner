package com.algopraxis.dhanniti.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Milestone 4: purchasing power for the current cycle. Computed on the server (ProjectionService); see backend/openapi.yaml.

@Serializable
enum class CycleMode {
    /** Payday to the day before the next payday. */
    @SerialName("payday") PAYDAY,
    @SerialName("calendar") CALENDAR,
}

@Serializable
data class Cycle(
    val start: LocalDate,
    val end: LocalDate,
    val mode: CycleMode,
    @SerialName("days_total") val daysTotal: Int,
    /** Including today. */
    @SerialName("days_left") val daysLeft: Int,
)

@Serializable
data class CycleIncomeSource(
    @SerialName("income_source_id") val incomeSourceId: Long,
    val name: String,
    val period: String,
    @SerialName("pay_date") val payDate: LocalDate,
    val expected: Money,
    val credited: Money? = null,
    /** Payday has passed and nothing is logged. */
    val awaiting: Boolean = false,
)

@Serializable
data class CycleIncome(
    val expected: Money,
    val credited: Money,
    /** Manual income and repayments of money lent. */
    val other: Money,
    val total: Money,
    val sources: List<CycleIncomeSource> = emptyList(),
)

@Serializable
data class ReservedForDues(
    val total: Money,
    val emis: Money,
    val cards: Money,
    val recurring: Money,
    /** From before the cycle and still unpaid. */
    val overdue: Money,
    val paid: Money,
    val open: Money,
)

@Serializable
data class DashboardReceivables(
    @SerialName("total_lent_outstanding") val totalLentOutstanding: Money,
    val people: Int,
    val overdue: Money,
)

@Serializable
data class DashboardAlert(
    val type: String,
    val message: String,
    /** For confirm_auto: the automatic entry to confirm. */
    @SerialName("transaction_id") val transactionId: Long? = null,
)

@Serializable
data class Dashboard(
    val cycle: Cycle,
    val income: CycleIncome,
    @SerialName("reserved_for_dues") val reservedForDues: ReservedForDues,
    @SerialName("spent_so_far") val spentSoFar: Money,
    @SerialName("lent_out") val lentOut: Money,
    /** May be negative: commitments exceed income. */
    @SerialName("purchasing_power") val purchasingPower: Money,
    @SerialName("per_day_left") val perDayLeft: Money,
    val receivables: DashboardReceivables,
    @SerialName("next_dues") val nextDues: List<Due> = emptyList(),
    val alerts: List<DashboardAlert> = emptyList(),
)

/**
 * The dashboard with a transaction made offline counted in, until the server's figures arrive: spending lowers the
 * purchasing power, income raises it. Outside the cycle it changes nothing.
 */
fun Dashboard.withPending(transaction: Transaction): Dashboard {
    if (transaction.occurredOn < cycle.start || transaction.occurredOn > cycle.end) return this
    val power = purchasingPower + transaction.signedAmount
    val perDay = Money(maxOf(0L, power.paise) / maxOf(1, cycle.daysLeft))
    return when (transaction.direction) {
        Direction.DEBIT -> copy(spentSoFar = spentSoFar + transaction.amount, purchasingPower = power, perDayLeft = perDay)
        Direction.CREDIT -> copy(
            income = income.copy(other = income.other + transaction.amount, total = income.total + transaction.amount),
            purchasingPower = power,
            perDayLeft = perDay,
        )
    }
}
