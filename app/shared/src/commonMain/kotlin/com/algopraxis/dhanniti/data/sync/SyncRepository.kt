package com.algopraxis.dhanniti.data.sync

import com.algopraxis.dhanniti.data.api.ApiException
import com.algopraxis.dhanniti.data.api.Connectivity
import com.algopraxis.dhanniti.data.api.LedgerApi
import com.algopraxis.dhanniti.data.api.ObligationsApi
import com.algopraxis.dhanniti.data.api.isNetworkError
import com.algopraxis.dhanniti.data.api.userMessage
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.data.db.CacheDao
import com.algopraxis.dhanniti.data.db.CacheEntity
import com.algopraxis.dhanniti.data.db.UserDao
import com.algopraxis.dhanniti.data.db.toEntity
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.data.ledger.LendingRepository
import com.algopraxis.dhanniti.data.ledger.ObligationsRepository
import com.algopraxis.dhanniti.data.reminders.RemindersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

data class SyncStatus(
    val online: Boolean = true,
    /** When the data on screen came from the server; null before the first sync. */
    val lastSyncedAt: Instant? = null,
    /** Offline changes still to send. */
    val pending: Int = 0,
    /** Offline changes the server refused: shown for review. */
    val failed: List<OutboxItem> = emptyList(),
)

/**
 * Keeps the app usable offline. On sign-in it shows the cached snapshot at once, then fetches a fresh one. Changes
 * made offline wait in the outbox and are sent, oldest first, before every sync; meanwhile they're shown on top of
 * the cached data. While offline it retries every [RETRY_SECONDS] seconds, and as soon as any request gets through.
 */
class SyncRepository(
    private val api: SyncApi,
    private val ledgerApi: LedgerApi,
    private val obligationsApi: ObligationsApi,
    private val cache: CacheDao,
    private val userDao: UserDao,
    private val outbox: OutboxRepository,
    private val ledger: LedgerRepository,
    private val obligations: ObligationsRepository,
    private val lending: LendingRepository,
    private val reminders: RemindersRepository,
    private val connectivity: Connectivity,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val lastSyncedAt = MutableStateFlow<Instant?>(null)

    val status: StateFlow<SyncStatus> = combine(connectivity.online, lastSyncedAt, outbox.items) { online, synced, items ->
        SyncStatus(online, synced, items.count { !it.failed }, items.filter { it.failed })
    }.stateIn(scope, SharingStarted.Eagerly, SyncStatus())

    fun start() {
        scope.launch {
            tokenStore.tokenFlow.collect { token ->
                if (token == null) {
                    cache.clear()
                    outbox.clear()
                    lastSyncedAt.value = null
                } else {
                    restore()
                    sync()
                }
            }
        }
        // Back online: send what's waiting and refresh.
        scope.launch {
            connectivity.online.filter { it }.collect {
                if (tokenStore.token() != null && outbox.all().any { !it.failed }) sync()
            }
        }
        // Offline: keep trying, so the switch back is noticed even if nothing else makes a request.
        scope.launch {
            while (true) {
                delay(RETRY_SECONDS * 1000L)
                if (!connectivity.online.value && tokenStore.token() != null) sync()
            }
        }
    }

    /** Shows the cached snapshot (and pending offline changes over it) without touching the network. */
    suspend fun restore() {
        val entry = cache.get(SNAPSHOT) ?: return
        val snapshot = runCatching { parseSnapshot(entry.json) }.getOrNull() ?: return // older format: wait for a sync
        apply(snapshot)
        lastSyncedAt.value = Instant.fromEpochMilliseconds(entry.savedAt)
    }

    /**
     * Sends pending changes, then replaces everything with a fresh snapshot and caches it.
     *
     * @return false when the server couldn't be reached
     */
    suspend fun sync(): Boolean = mutex.withLock {
        try {
            flush()
            val json = api.snapshotJson()
            val snapshot = parseSnapshot(json)
            userDao.upsert(snapshot.user.toEntity())
            apply(snapshot)
            val now = clock.now()
            cache.put(CacheEntity(SNAPSHOT, json, now.toEpochMilliseconds()))
            lastSyncedAt.value = now
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException.Unauthorized) {
            false // the session is gone; the app shows the login screen
        } catch (e: Exception) {
            if (!e.isNetworkError()) throw e
            false
        }
    }

    /** Drops an offline change the server refused, putting the cached data back as it was. */
    suspend fun discard(item: OutboxItem) {
        outbox.remove(item.id)
        restore()
    }

    /** Sends queued changes oldest first; stops at the first one the network can't deliver. */
    private suspend fun flush() {
        for (item in outbox.all().filter { !it.failed }) {
            try {
                when (val change = item.change) {
                    is PendingChange.CreateTransaction -> ledgerApi.createTransaction(change.input.copy(clientRef = change.clientRef))
                    is PendingChange.PayDue -> obligationsApi.payDue(change.dueId, change.input)
                }
                outbox.remove(item.id)
            } catch (e: ApiException.Http) {
                // 409: the due was already paid or skipped, so there's nothing left to do.
                if (e.status == 409 && item.change is PendingChange.PayDue) outbox.remove(item.id) else outbox.markFailed(item, e.userMessage())
            } catch (e: ApiException.Validation) {
                outbox.markFailed(item, e.userMessage())
            }
        }
    }

    private suspend fun apply(snapshot: SyncSnapshot) {
        ledger.applySnapshot(snapshot.accounts, snapshot.categories, snapshot.incomeSources, snapshot.transactions, snapshot.dashboard)
        obligations.applySnapshot(snapshot.loans, snapshot.creditCards, snapshot.recurringPayments, snapshot.dues)
        lending.applySnapshot(snapshot.lendings, snapshot.lendingSummary)
        reminders.applySnapshot(snapshot.reminderSettings)
        // Offline changes not yet on the server go on top, so they don't vanish from screen after a refresh.
        outbox.all().forEach { item ->
            when (val change = item.change) {
                is PendingChange.CreateTransaction -> ledger.showPendingTransaction(change.clientRef, change.input)
                is PendingChange.PayDue -> obligations.showPendingPayment(change.dueId, change.input)
            }
        }
    }

    private companion object {
        const val SNAPSHOT = "snapshot"
        const val RETRY_SECONDS = 30
    }
}
