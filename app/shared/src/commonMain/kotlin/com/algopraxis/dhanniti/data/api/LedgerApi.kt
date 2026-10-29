package com.algopraxis.dhanniti.data.api

import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.AccountType
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.CategoryKind
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.IncomeSource
import com.algopraxis.dhanniti.domain.LedgerSummary
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.PayRule
import com.algopraxis.dhanniti.domain.Transaction
import com.algopraxis.dhanniti.domain.WeekendShift
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Milestone 1 endpoints: accounts, categories, transactions, income sources. See backend/openapi.yaml. */
class LedgerApi(private val client: HttpClient) {

    suspend fun accounts(): List<Account> = client.get("accounts").bodyOrThrow<Data<List<Account>>>().data

    suspend fun createAccount(input: AccountInput): Account =
        client.post("accounts") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Account>>().data

    suspend fun updateAccount(id: Long, input: AccountInput): Account =
        client.put("accounts/$id") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Account>>().data

    suspend fun archiveAccount(id: Long) = client.delete("accounts/$id").ensureSuccess()

    suspend fun categories(): List<Category> = client.get("categories").bodyOrThrow<Data<List<Category>>>().data

    suspend fun createCategory(input: CategoryInput): Category =
        client.post("categories") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Category>>().data

    suspend fun updateCategory(id: Long, input: CategoryInput): Category =
        client.put("categories/$id") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Category>>().data

    suspend fun archiveCategory(id: Long) = client.delete("categories/$id").ensureSuccess()

    suspend fun transactions(
        direction: Direction? = null,
        cursor: String? = null,
        perPage: Int = 50,
    ): TransactionPage = client.get("transactions") {
        direction?.let { parameter("direction", if (it == Direction.CREDIT) "credit" else "debit") }
        cursor?.let { parameter("cursor", it) }
        parameter("per_page", perPage)
    }.bodyOrThrow()

    suspend fun summary(from: LocalDate, to: LocalDate): LedgerSummary = client.get("transactions/summary") {
        parameter("from", from.toString())
        parameter("to", to.toString())
    }.bodyOrThrow<Data<LedgerSummary>>().data

    suspend fun createTransaction(input: TransactionInput): Transaction =
        client.post("transactions") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Transaction>>().data

    suspend fun updateTransaction(id: Long, input: TransactionInput): Transaction =
        client.put("transactions/$id") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Transaction>>().data

    suspend fun deleteTransaction(id: Long) = client.delete("transactions/$id").ensureSuccess()

    suspend fun incomeSources(): List<IncomeSource> =
        client.get("income-sources").bodyOrThrow<Data<List<IncomeSource>>>().data

    suspend fun createIncomeSource(input: IncomeSourceInput): IncomeSource =
        client.post("income-sources") { jsonBody(); setBody(input) }.bodyOrThrow<Data<IncomeSource>>().data

    suspend fun updateIncomeSource(id: Long, input: IncomeSourceInput): IncomeSource =
        client.put("income-sources/$id") { jsonBody(); setBody(input) }.bodyOrThrow<Data<IncomeSource>>().data

    suspend fun deleteIncomeSource(id: Long) = client.delete("income-sources/$id").ensureSuccess()

    suspend fun logIncomeCredit(id: Long, input: IncomeCreditInput): Transaction =
        client.post("income-sources/$id/credits") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Transaction>>().data
}

@Serializable
data class Data<T>(val data: T)

@Serializable
data class TransactionPage(val data: List<Transaction>, val meta: PageMeta)

@Serializable
data class PageMeta(@SerialName("next_cursor") val nextCursor: String? = null)

@Serializable
data class AccountInput(
    val name: String,
    val type: AccountType,
    @SerialName("opening_balance") val openingBalance: Money,
    @SerialName("is_primary") val isPrimary: Boolean,
)

@Serializable
data class CategoryInput(val name: String, val kind: CategoryKind, val icon: String)

@Serializable
data class TransactionInput(
    @SerialName("account_id") val accountId: Long,
    @SerialName("category_id") val categoryId: Long?,
    val direction: Direction,
    val amount: Money,
    @SerialName("occurred_on") val occurredOn: LocalDate,
    val description: String?,
)

@Serializable
data class IncomeSourceInput(
    val name: String,
    @SerialName("account_id") val accountId: Long,
    val amount: Money,
    @SerialName("is_variable") val isVariable: Boolean,
    @SerialName("pay_rule") val payRule: PayRule,
    @SerialName("pay_day") val payDay: Int?,
    @SerialName("weekend_shift") val weekendShift: WeekendShift,
    @SerialName("auto_credit") val autoCredit: Boolean,
)

@Serializable
data class IncomeCreditInput(
    val period: String,
    val amount: Money? = null,
    @SerialName("occurred_on") val occurredOn: LocalDate? = null,
)
