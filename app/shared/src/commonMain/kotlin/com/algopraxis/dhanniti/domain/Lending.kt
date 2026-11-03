package com.algopraxis.dhanniti.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Milestone 3: money lent to people. Outstanding amounts are receivables, never spendable. Wire format: backend/openapi.yaml.

@Serializable
enum class LendingStatus(val label: String) {
    @SerialName("open") OPEN("Owed"),
    @SerialName("partially_repaid") PARTIALLY_REPAID("Part repaid"),
    @SerialName("settled") SETTLED("Settled"),
    @SerialName("written_off") WRITTEN_OFF("Written off"),
}

@Serializable
data class LendingRepayment(
    val id: Long,
    val amount: Money,
    @SerialName("received_on") val receivedOn: LocalDate,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("transaction_id") val transactionId: Long? = null,
)

@Serializable
data class Lending(
    val id: Long,
    @SerialName("person_name") val personName: String,
    val contact: String? = null,
    val amount: Money,
    val repaid: Money,
    /** Still owed; zero once settled or written off. */
    val outstanding: Money,
    @SerialName("lent_on") val lentOn: LocalDate,
    @SerialName("expected_by") val expectedBy: LocalDate? = null,
    val notes: String? = null,
    /** The account the money left; null when it isn't tracked. */
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("transaction_id") val transactionId: Long? = null,
    val status: LendingStatus,
    @SerialName("closed_on") val closedOn: LocalDate? = null,
    @SerialName("is_overdue") val isOverdue: Boolean = false,
    val repayments: List<LendingRepayment> = emptyList(),
) {
    val isOutstanding: Boolean get() = status == LendingStatus.OPEN || status == LendingStatus.PARTIALLY_REPAID
}

@Serializable
data class PersonBalance(
    /** As written on their latest lending. */
    val name: String,
    val contact: String? = null,
    val lent: Money,
    val repaid: Money,
    @SerialName("written_off") val writtenOff: Money,
    val outstanding: Money,
    @SerialName("open_count") val openCount: Int,
    val overdue: Boolean,
    @SerialName("next_expected_by") val nextExpectedBy: LocalDate? = null,
    @SerialName("last_lent_on") val lastLentOn: LocalDate,
    @SerialName("lending_ids") val lendingIds: List<Long> = emptyList(),
)

@Serializable
data class Receivables(
    @SerialName("total_outstanding") val totalOutstanding: Money = Money.ZERO,
    @SerialName("overdue_total") val overdueTotal: Money = Money.ZERO,
    @SerialName("people_owing") val peopleOwing: Int = 0,
    val people: List<PersonBalance> = emptyList(),
)

/** "  Ravi  Kumar" and "ravi kumar" are the same person, as on the server. */
fun personKey(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase()
