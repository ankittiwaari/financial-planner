package com.algopraxis.dhanniti.data.api

import com.algopraxis.dhanniti.domain.Lending
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.Receivables
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Milestone 3 endpoints: money lent and repayments. See backend/openapi.yaml. */
class LendingApi(private val client: HttpClient) {

    suspend fun lendings(): List<Lending> = client.get("lendings").bodyOrThrow<Data<List<Lending>>>().data

    suspend fun summary(): Receivables = client.get("lendings/summary").bodyOrThrow<Data<Receivables>>().data

    suspend fun createLending(input: LendingInput): Lending =
        client.post("lendings") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Lending>>().data

    suspend fun updateLending(id: Long, input: LendingInput): Lending =
        client.put("lendings/$id") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Lending>>().data

    suspend fun deleteLending(id: Long) = client.delete("lendings/$id").ensureSuccess()

    suspend fun repay(id: Long, input: RepaymentInput): Lending =
        client.post("lendings/$id/repayments") { jsonBody(); setBody(input) }.bodyOrThrow<Data<Lending>>().data

    suspend fun deleteRepayment(id: Long, repaymentId: Long): Lending =
        client.delete("lendings/$id/repayments/$repaymentId").bodyOrThrow<Data<Lending>>().data

    suspend fun writeOff(id: Long): Lending = client.post("lendings/$id/write-off").bodyOrThrow<Data<Lending>>().data

    suspend fun reopen(id: Long): Lending = client.post("lendings/$id/reopen").bodyOrThrow<Data<Lending>>().data
}

@Serializable
data class LendingInput(
    @SerialName("person_name") val personName: String,
    val contact: String?,
    val amount: Money,
    @SerialName("lent_on") val lentOn: LocalDate,
    @SerialName("expected_by") val expectedBy: LocalDate?,
    val notes: String?,
    /** Null: the money didn't leave a tracked account. */
    @SerialName("account_id") val accountId: Long?,
)

@Serializable
data class RepaymentInput(
    val amount: Money,
    @SerialName("received_on") val receivedOn: LocalDate,
    /** Null: the money didn't go into a tracked account. */
    @SerialName("account_id") val accountId: Long? = null,
)
