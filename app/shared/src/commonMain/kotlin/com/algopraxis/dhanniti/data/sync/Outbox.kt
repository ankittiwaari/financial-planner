package com.algopraxis.dhanniti.data.sync

import com.algopraxis.dhanniti.data.api.ApiJson
import com.algopraxis.dhanniti.data.api.PayDueInput
import com.algopraxis.dhanniti.data.api.TransactionInput
import com.algopraxis.dhanniti.data.db.OutboxDao
import com.algopraxis.dhanniti.data.db.OutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** A change made while offline, sent once the server is reachable. */
@Serializable
sealed interface PendingChange {
    /** For the review list: what this was. */
    val summary: String

    /** A new transaction. [clientRef] lets the server ignore a resend of one it already saved. */
    @Serializable
    @SerialName("create_transaction")
    data class CreateTransaction(val clientRef: String, val input: TransactionInput, override val summary: String) : PendingChange

    @Serializable
    @SerialName("pay_due")
    data class PayDue(val dueId: Long, val input: PayDueInput, override val summary: String) : PendingChange
}

data class OutboxItem(val id: Long, val change: PendingChange, val lastError: String?) {
    val failed: Boolean get() = lastError != null
}

/** Asks the platform to send queued changes once there's a connection, even if the app is closed by then. */
interface BackgroundSync {
    fun requestWhenOnline()
}

object NoBackgroundSync : BackgroundSync {
    override fun requestWhenOnline() = Unit
}

/** Changes made offline, oldest first, stored so they survive the app being closed. */
class OutboxRepository(
    private val dao: OutboxDao,
    private val background: BackgroundSync,
    private val clock: Clock = Clock.System,
) {
    val items: Flow<List<OutboxItem>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toItem() } }

    suspend fun all(): List<OutboxItem> = dao.all().mapNotNull { it.toItem() }

    suspend fun add(change: PendingChange) {
        dao.upsert(OutboxEntity(payload = ApiJson.encodeToString(PendingChange.serializer(), change), createdAt = clock.now().toEpochMilliseconds()))
        background.requestWhenOnline()
    }

    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun markFailed(item: OutboxItem, error: String) {
        dao.upsert(OutboxEntity(item.id, ApiJson.encodeToString(PendingChange.serializer(), item.change), clock.now().toEpochMilliseconds(), error))
    }

    suspend fun clear() = dao.clear()

    // A row that no longer parses (an older app version's format) is dropped rather than blocking the queue.
    private fun OutboxEntity.toItem(): OutboxItem? =
        runCatching { OutboxItem(id, ApiJson.decodeFromString(PendingChange.serializer(), payload), lastError) }.getOrNull()
}
