package com.algopraxis.dhanniti.data.api

import com.algopraxis.dhanniti.domain.CardStatement
import com.algopraxis.dhanniti.domain.CreditCard
import com.algopraxis.dhanniti.domain.Due
import com.algopraxis.dhanniti.domain.EstimateMode
import com.algopraxis.dhanniti.domain.Frequency
import com.algopraxis.dhanniti.domain.Loan
import com.algopraxis.dhanniti.domain.LoanScheduleRow
import com.algopraxis.dhanniti.domain.LoanType
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.RecurringPayment
import com.algopraxis.dhanniti.domain.ReminderSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Milestone 2 endpoints (loans, credit cards, recurring payments, dues) and reminder settings. See backend/openapi.yaml. */
class ObligationsApi(private val client: HttpClient) {

    suspend fun loans(): List<Loan> = client.get("loans").bodyOrThrow<Data<List<Loan>>>().data

    suspend fun createLoan(input: LoanInput): Loan =
        client.post("loans") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Loan>>().data

    suspend fun updateLoan(id: Long, input: LoanInput): Loan =
        client.put("loans/$id") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Loan>>().data

    suspend fun deleteLoan(id: Long) = client.delete("loans/$id").ensureSuccess()

    suspend fun closeLoan(id: Long): Loan = client.post("loans/$id/close").bodyOrThrow<Data<Loan>>().data

    suspend fun loanSchedule(id: Long): List<LoanScheduleRow> =
        client.get("loans/$id/schedule").bodyOrThrow<Data<List<LoanScheduleRow>>>().data

    suspend fun creditCards(): List<CreditCard> = client.get("credit-cards").bodyOrThrow<Data<List<CreditCard>>>().data

    suspend fun createCreditCard(input: CreditCardInput): CreditCard =
        client.post("credit-cards") { jsonBody(); setBody(input) }.bodyOrThrow<Data<CreditCard>>().data

    suspend fun updateCreditCard(id: Long, input: CreditCardInput): CreditCard =
        client.put("credit-cards/$id") { jsonBody(); setBody(input) }.bodyOrThrow<Data<CreditCard>>().data

    suspend fun deleteCreditCard(id: Long) = client.delete("credit-cards/$id").ensureSuccess()

    suspend fun enterStatement(cardId: Long, input: StatementInput): CardStatement =
        client.post("credit-cards/$cardId/statements") { jsonBody(); setBody(input) }.bodyOrThrow<Data<CardStatement>>().data

    suspend fun recurringPayments(): List<RecurringPayment> =
        client.get("recurring-payments").bodyOrThrow<Data<List<RecurringPayment>>>().data

    suspend fun createRecurringPayment(input: RecurringPaymentInput): RecurringPayment =
        client.post("recurring-payments") { jsonBody(); setBody(input) }.bodyOrThrow<Data<RecurringPayment>>().data

    suspend fun updateRecurringPayment(id: Long, input: RecurringPaymentInput): RecurringPayment =
        client.put("recurring-payments/$id") { jsonBody(); setBody(input) }.bodyOrThrow<Data<RecurringPayment>>().data

    suspend fun deleteRecurringPayment(id: Long) = client.delete("recurring-payments/$id").ensureSuccess()

    /** Server defaults: 30 days back to 45 ahead, plus anything overdue. */
    suspend fun dues(): List<Due> = client.get("dues").bodyOrThrow<Data<List<Due>>>().data

    suspend fun payDue(id: Long, input: PayDueInput): Due =
        client.post("dues/$id/pay") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Due>>().data

    suspend fun skipDue(id: Long): Due = client.post("dues/$id/skip").bodyOrThrow<Data<Due>>().data

    suspend fun reopenDue(id: Long): Due = client.post("dues/$id/reopen").bodyOrThrow<Data<Due>>().data

    suspend fun due(id: Long): Due = client.get("dues/$id").bodyOrThrow<Data<Due>>().data

    suspend fun reminderSettings(): ReminderSettings =
        client.get("settings/reminders").bodyOrThrow<Data<ReminderSettings>>().data

    suspend fun updateReminderSettings(settings: ReminderSettings): ReminderSettings =
        client.put("settings/reminders") { jsonBody(); setBody(settings) }.bodyOrThrow<Data<ReminderSettings>>().data
}

@Serializable
data class LoanInput(
    val name: String,
    val lender: String?,
    val type: LoanType,
    val principal: Money,
    @SerialName("interest_rate") val interestRate: Double,
    @SerialName("tenure_months") val tenureMonths: Int,
    @SerialName("emi_amount") val emiAmount: Money?,
    @SerialName("first_emi_date") val firstEmiDate: LocalDate,
    @SerialName("account_id") val accountId: Long,
    @SerialName("auto_debit") val autoDebit: Boolean,
)

@Serializable
data class CreditCardInput(
    val nickname: String,
    val last4: String?,
    val issuer: String?,
    @SerialName("credit_limit") val creditLimit: Money?,
    @SerialName("statement_day") val statementDay: Int,
    @SerialName("due_day") val dueDay: Int,
    @SerialName("pay_from_account_id") val payFromAccountId: Long,
    @SerialName("estimate_mode") val estimateMode: EstimateMode,
    @SerialName("fixed_estimate") val fixedEstimate: Money?,
)

@Serializable
data class StatementInput(
    val period: String,
    @SerialName("total_due") val totalDue: Money,
    @SerialName("min_due") val minDue: Money? = null,
)

@Serializable
data class RecurringPaymentInput(
    val name: String,
    val amount: Money,
    val frequency: Frequency,
    val day: Int,
    @SerialName("account_id") val accountId: Long,
    @SerialName("category_id") val categoryId: Long?,
    @SerialName("auto_debit") val autoDebit: Boolean,
    /** The first payment date: quarterly and yearly payments repeat from this month. */
    @SerialName("active_from") val activeFrom: LocalDate,
)

@Serializable
data class PayDueInput(
    val amount: Money? = null,
    @SerialName("paid_on") val paidOn: LocalDate? = null,
    @SerialName("account_id") val accountId: Long? = null,
)
