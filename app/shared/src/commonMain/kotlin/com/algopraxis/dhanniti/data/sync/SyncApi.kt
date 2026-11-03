package com.algopraxis.dhanniti.data.sync

import com.algopraxis.dhanniti.data.api.ApiJson
import com.algopraxis.dhanniti.data.api.TransactionPage
import com.algopraxis.dhanniti.data.api.UserDto
import com.algopraxis.dhanniti.data.api.bodyOrThrow
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.CreditCard
import com.algopraxis.dhanniti.domain.Dashboard
import com.algopraxis.dhanniti.domain.Due
import com.algopraxis.dhanniti.domain.IncomeSource
import com.algopraxis.dhanniti.domain.Lending
import com.algopraxis.dhanniti.domain.Loan
import com.algopraxis.dhanniti.domain.Receivables
import com.algopraxis.dhanniti.domain.RecurringPayment
import com.algopraxis.dhanniti.domain.ReminderSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Everything the app shows, in one response (GET /sync). Each part is shaped like its own endpoint. */
@Serializable
data class SyncSnapshot(
    @SerialName("server_time") val serverTime: String,
    val user: UserDto,
    val accounts: List<Account>,
    val categories: List<Category>,
    @SerialName("income_sources") val incomeSources: List<IncomeSource>,
    val transactions: TransactionPage,
    val loans: List<Loan>,
    @SerialName("credit_cards") val creditCards: List<CreditCard>,
    @SerialName("recurring_payments") val recurringPayments: List<RecurringPayment>,
    val dues: List<Due>,
    val lendings: List<Lending>,
    @SerialName("lending_summary") val lendingSummary: Receivables,
    @SerialName("reminder_settings") val reminderSettings: ReminderSettings,
    val dashboard: Dashboard,
)

class SyncApi(private val client: HttpClient) {
    /** The snapshot as raw JSON, so it can be cached as it came and parsed by the same code later. */
    suspend fun snapshotJson(): String =
        ApiJson.encodeToString(JsonObject.serializer(), client.get("sync").bodyOrThrow<SnapshotEnvelope>().data)

    @Serializable
    private data class SnapshotEnvelope(val data: JsonObject)
}

fun parseSnapshot(json: String): SyncSnapshot = ApiJson.decodeFromString(SyncSnapshot.serializer(), json)
