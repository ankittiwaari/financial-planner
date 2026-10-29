package com.algopraxis.dhanniti.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Domain models double as the API wire format (backend/openapi.yaml). Money serialises as integer paise.

@Serializable
enum class AccountType(val label: String) {
    @SerialName("bank") BANK("Bank"),
    @SerialName("cash") CASH("Cash"),
    @SerialName("wallet") WALLET("Wallet"),
}

@Serializable
data class Account(
    val id: Long,
    val name: String,
    val type: AccountType,
    @SerialName("opening_balance") val openingBalance: Money,
    val balance: Money,
    @SerialName("is_primary") val isPrimary: Boolean,
    val archived: Boolean = false,
)

@Serializable
enum class CategoryKind(val label: String) {
    @SerialName("income") INCOME("Income"),
    @SerialName("expense") EXPENSE("Expense"),
}

@Serializable
data class Category(
    val id: Long,
    val name: String,
    val kind: CategoryKind,
    val icon: String,
    val archived: Boolean = false,
)

@Serializable
enum class Direction {
    /** Money in. */
    @SerialName("credit") CREDIT,

    /** Money out. */
    @SerialName("debit") DEBIT;

    val categoryKind: CategoryKind get() = if (this == CREDIT) CategoryKind.INCOME else CategoryKind.EXPENSE
}

@Serializable
data class Transaction(
    val id: Long,
    @SerialName("account_id") val accountId: Long,
    @SerialName("category_id") val categoryId: Long? = null,
    val direction: Direction,
    /** Always positive; [direction] gives the sign. */
    val amount: Money,
    @SerialName("occurred_on") val occurredOn: LocalDate,
    val description: String? = null,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("source_id") val sourceId: Long? = null,
    val period: String? = null,
    @SerialName("is_auto") val isAuto: Boolean = false,
) {
    val signedAmount: Money get() = if (direction == Direction.CREDIT) amount else -amount
}

@Serializable
data class LedgerSummary(
    val from: LocalDate,
    val to: LocalDate,
    @SerialName("credit_total") val creditTotal: Money,
    @SerialName("debit_total") val debitTotal: Money,
    val net: Money,
)

@Serializable
enum class PayRule(val label: String) {
    @SerialName("fixed_day") FIXED_DAY("On a fixed date"),
    @SerialName("last_working_day") LAST_WORKING_DAY("Last working day"),
    @SerialName("last_day") LAST_DAY("Last day of month"),
}

@Serializable
enum class WeekendShift(val label: String) {
    @SerialName("before") BEFORE("Friday before"),
    @SerialName("after") AFTER("Monday after"),
    @SerialName("none") NONE("Don't move"),
}

@Serializable
data class IncomeSource(
    val id: Long,
    val name: String,
    @SerialName("account_id") val accountId: Long,
    val amount: Money,
    @SerialName("is_variable") val isVariable: Boolean,
    @SerialName("pay_rule") val payRule: PayRule,
    @SerialName("pay_day") val payDay: Int? = null,
    @SerialName("weekend_shift") val weekendShift: WeekendShift,
    @SerialName("auto_credit") val autoCredit: Boolean,
    @SerialName("active_from") val activeFrom: LocalDate,
    @SerialName("active_to") val activeTo: LocalDate? = null,
    @SerialName("next_pay_date") val nextPayDate: LocalDate? = null,
    @SerialName("last_credited_period") val lastCreditedPeriod: String? = null,
) {
    val scheduleLabel: String
        get() = when (payRule) {
            PayRule.FIXED_DAY -> "Every month on the ${payDay?.let(::ordinal) ?: "?"}"
            PayRule.LAST_WORKING_DAY -> "Last working day of the month"
            PayRule.LAST_DAY -> "Last day of the month"
        }
}

/** 1 → "1st", 22 → "22nd", 13 → "13th". */
fun ordinal(day: Int): String {
    val suffix = if (day % 100 in 11..13) "th" else when (day % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
