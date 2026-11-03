package com.algopraxis.dhanniti.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Milestone 2: loans, credit cards, recurring payments and the dues they produce. Wire format: backend/openapi.yaml.

@Serializable
enum class LoanType(val label: String) {
    @SerialName("home") HOME("Home"),
    @SerialName("car") CAR("Car"),
    @SerialName("personal") PERSONAL("Personal"),
    @SerialName("education") EDUCATION("Education"),
    @SerialName("other") OTHER("Other"),
}

@Serializable
enum class LoanStatus {
    @SerialName("active") ACTIVE,
    @SerialName("closed") CLOSED,
}

@Serializable
data class Loan(
    val id: Long,
    val name: String,
    val lender: String? = null,
    val type: LoanType,
    val principal: Money,
    /** Annual %, reducing balance. */
    @SerialName("interest_rate") val interestRate: Double,
    @SerialName("tenure_months") val tenureMonths: Int,
    /** The bank's EMI as entered; null when the server computes it. */
    @SerialName("emi_amount") val emiAmount: Money? = null,
    /** The EMI in use. */
    val emi: Money,
    @SerialName("first_emi_date") val firstEmiDate: LocalDate,
    @SerialName("last_emi_date") val lastEmiDate: LocalDate? = null,
    @SerialName("account_id") val accountId: Long,
    @SerialName("auto_debit") val autoDebit: Boolean,
    val status: LoanStatus,
    @SerialName("closed_on") val closedOn: LocalDate? = null,
    val outstanding: Money,
    @SerialName("emis_paid") val emisPaid: Int,
    @SerialName("emis_left") val emisLeft: Int,
    @SerialName("next_emi_date") val nextEmiDate: LocalDate? = null,
    @SerialName("total_interest") val totalInterest: Money,
) {
    val isActive: Boolean get() = status == LoanStatus.ACTIVE
}

@Serializable
data class LoanScheduleRow(
    val installment: Int,
    @SerialName("due_date") val dueDate: LocalDate,
    val emi: Money,
    val interest: Money,
    val principal: Money,
    /** Left after this EMI. */
    val outstanding: Money,
    val paid: Boolean,
)

@Serializable
enum class EstimateMode(val label: String) {
    @SerialName("last_statement") LAST_STATEMENT("Last bill"),
    @SerialName("avg_3") AVERAGE_3("Average of last 3"),
    @SerialName("fixed") FIXED("Fixed amount"),
}

@Serializable
enum class StatementStatus(val label: String) {
    @SerialName("estimated") ESTIMATED("Estimate"),
    @SerialName("entered") ENTERED("Unpaid"),
    @SerialName("partially_paid") PARTIALLY_PAID("Part paid"),
    @SerialName("paid") PAID("Paid"),
}

@Serializable
data class CardStatement(
    val id: Long,
    @SerialName("credit_card_id") val creditCardId: Long,
    val period: String,
    @SerialName("statement_date") val statementDate: LocalDate,
    @SerialName("due_date") val dueDate: LocalDate,
    @SerialName("total_due") val totalDue: Money,
    @SerialName("min_due") val minDue: Money? = null,
    @SerialName("amount_paid") val amountPaid: Money,
    val status: StatementStatus,
)

@Serializable
data class CreditCard(
    val id: Long,
    val nickname: String,
    val last4: String? = null,
    val issuer: String? = null,
    @SerialName("credit_limit") val creditLimit: Money? = null,
    @SerialName("statement_day") val statementDay: Int,
    @SerialName("due_day") val dueDay: Int,
    @SerialName("pay_from_account_id") val payFromAccountId: Long,
    @SerialName("estimate_mode") val estimateMode: EstimateMode,
    @SerialName("fixed_estimate") val fixedEstimate: Money? = null,
    @SerialName("latest_statement") val latestStatement: CardStatement? = null,
) {
    /** "ICICI Amazon Pay ••4321". */
    val label: String get() = if (last4 != null) "$nickname ••$last4" else nickname
}

@Serializable
enum class Frequency(val label: String, val months: Int) {
    @SerialName("monthly") MONTHLY("Monthly", 1),
    @SerialName("quarterly") QUARTERLY("Quarterly", 3),
    @SerialName("yearly") YEARLY("Yearly", 12),
}

@Serializable
data class RecurringPayment(
    val id: Long,
    val name: String,
    val amount: Money,
    val frequency: Frequency,
    val day: Int,
    @SerialName("account_id") val accountId: Long,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("auto_debit") val autoDebit: Boolean,
    @SerialName("active_from") val activeFrom: LocalDate,
    @SerialName("active_to") val activeTo: LocalDate? = null,
    @SerialName("next_date") val nextDate: LocalDate? = null,
) {
    val scheduleLabel: String
        get() = when (frequency) {
            Frequency.MONTHLY -> "Every month on the ${ordinal(day)}"
            Frequency.QUARTERLY -> "Every 3 months on the ${ordinal(day)}"
            Frequency.YEARLY -> "Every year on ${ordinal(day)} ${formatPeriod(activeFrom.periodKey()).substringBefore(' ')}"
        }
}

@Serializable
enum class DueStatus(val label: String) {
    @SerialName("upcoming") UPCOMING("Upcoming"),
    /** Unpaid and dated before today. */
    @SerialName("overdue") OVERDUE("Overdue"),
    @SerialName("paid") PAID("Paid"),
    @SerialName("skipped") SKIPPED("Skipped"),
}

@Serializable
enum class DueSource(val emoji: String) {
    @SerialName("loan") LOAN("🏦"),
    @SerialName("card_statement") CARD("💳"),
    @SerialName("recurring_payment") RECURRING("🔁"),
}

@Serializable
data class Due(
    val id: Long,
    @SerialName("source_type") val sourceType: DueSource,
    @SerialName("source_id") val sourceId: Long,
    val period: String,
    val title: String,
    val amount: Money,
    @SerialName("due_date") val dueDate: LocalDate,
    val status: DueStatus,
    @SerialName("paid_on") val paidOn: LocalDate? = null,
    @SerialName("paid_amount") val paidAmount: Money? = null,
    @SerialName("transaction_id") val transactionId: Long? = null,
    /** The account it's paid from by default; null once its source is deleted. */
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("auto_debit") val autoDebit: Boolean = false,
    /** A card bill whose real statement isn't entered yet. */
    @SerialName("is_estimate") val isEstimate: Boolean = false,
    @SerialName("min_due") val minDue: Money? = null,
) {
    val isOpen: Boolean get() = status == DueStatus.UPCOMING || status == DueStatus.OVERDUE

    companion object {
        /** Stands in for a due that isn't loaded. */
        fun placeholder(id: Long) = Due(
            id = id, sourceType = DueSource.RECURRING, sourceId = 0, period = "", title = "", amount = Money.ZERO,
            dueDate = LocalDate(1970, 1, 1), status = DueStatus.PAID,
        )
    }
}
