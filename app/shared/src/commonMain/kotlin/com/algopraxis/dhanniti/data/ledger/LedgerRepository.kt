package com.algopraxis.dhanniti.data.ledger

import com.algopraxis.dhanniti.data.api.AccountInput
import com.algopraxis.dhanniti.data.api.CategoryInput
import com.algopraxis.dhanniti.data.api.IncomeCreditInput
import com.algopraxis.dhanniti.data.api.IncomeSourceInput
import com.algopraxis.dhanniti.data.api.LedgerApi
import com.algopraxis.dhanniti.data.api.TransactionInput
import com.algopraxis.dhanniti.data.api.TransactionPage
import com.algopraxis.dhanniti.data.api.isNetworkError
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.data.sync.OutboxRepository
import com.algopraxis.dhanniti.data.sync.PendingChange
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.Dashboard
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.IncomeSource
import com.algopraxis.dhanniti.domain.LedgerSummary
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.Transaction
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatShort
import com.algopraxis.dhanniti.domain.withPending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.absoluteValue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Transactions loaded so far, newest first. */
data class TransactionFeed(
    val items: List<Transaction> = emptyList(),
    val nextCursor: String? = null,
    val loaded: Boolean = false,
) {
    val hasMore: Boolean get() = nextCursor != null
}

/**
 * Single in-memory source of truth for the signed-in user's ledger. Screens observe the flows; every write
 * goes through here so all screens stay in step. Balances are computed by the server, so writes that move
 * money re-fetch accounts. SyncRepository fills these from the offline cache and the /sync snapshot; a new
 * transaction made offline is queued in the [outbox] and shown straight away.
 */
class LedgerRepository(
    private val api: LedgerApi,
    tokenStore: TokenStore,
    scope: CoroutineScope,
    private val outbox: OutboxRepository,
) {
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _incomeSources = MutableStateFlow<List<IncomeSource>>(emptyList())
    val incomeSources: StateFlow<List<IncomeSource>> = _incomeSources.asStateFlow()

    private val _transactions = MutableStateFlow(TransactionFeed())
    val transactions: StateFlow<TransactionFeed> = _transactions.asStateFlow()

    private val _dashboard = MutableStateFlow<Dashboard?>(null)

    /** Purchasing power for the current cycle; null until loaded. */
    val dashboard: StateFlow<Dashboard?> = _dashboard.asStateFlow()

    init {
        // Drop the previous user's data the moment they sign out.
        scope.launch { tokenStore.tokenFlow.collect { if (it == null) clear() } }
    }

    suspend fun refreshAll() = coroutineScope {
        launch { refreshAccounts() }
        launch { refreshCategories() }
        launch { refreshIncomeSources() }
        launch { refreshTransactions() }
    }

    suspend fun refreshAccounts() {
        _accounts.value = api.accounts()
    }

    suspend fun refreshCategories() {
        _categories.value = api.categories()
    }

    suspend fun refreshIncomeSources() {
        _incomeSources.value = api.incomeSources()
    }

    suspend fun refreshTransactions() {
        val page = api.transactions()
        _transactions.value = TransactionFeed(page.data, page.meta.nextCursor, loaded = true)
    }

    suspend fun loadMoreTransactions() {
        val cursor = _transactions.value.nextCursor ?: return
        val page = api.transactions(cursor = cursor)
        _transactions.update { feed ->
            feed.copy(items = (feed.items + page.data).distinctBy { it.id }, nextCursor = page.meta.nextCursor)
        }
    }

    suspend fun summary(from: LocalDate, to: LocalDate): LedgerSummary = api.summary(from, to)

    suspend fun refreshDashboard() {
        _dashboard.value = api.dashboard()
    }

    // --- accounts

    suspend fun saveAccount(id: Long?, input: AccountInput): Account {
        val saved = if (id == null) api.createAccount(input) else api.updateAccount(id, input)
        refreshAccounts() // primary flag may have moved to this account
        return saved
    }

    suspend fun archiveAccount(id: Long) {
        api.archiveAccount(id)
        refreshAccounts()
    }

    // --- categories

    suspend fun saveCategory(id: Long?, input: CategoryInput): Category {
        val saved = if (id == null) api.createCategory(input) else api.updateCategory(id, input)
        _categories.update { list -> (list.filterNot { it.id == saved.id } + saved).sortedWith(categoryOrder) }
        return saved
    }

    suspend fun archiveCategory(id: Long) {
        api.archiveCategory(id)
        _categories.update { list -> list.filterNot { it.id == id } }
    }

    // --- transactions

    /** A new transaction is queued if the server can't be reached; edits need a connection. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun saveTransaction(id: Long?, input: TransactionInput): Transaction {
        if (id != null) {
            return api.updateTransaction(id, input).also { upsertTransaction(it); refreshAccounts() }
        }
        val withRef = input.copy(clientRef = input.clientRef ?: Uuid.random().toString())
        val saved = try {
            api.createTransaction(withRef)
        } catch (e: Exception) {
            if (!e.isNetworkError()) throw e
            outbox.add(PendingChange.CreateTransaction(withRef.clientRef!!, withRef, summary(withRef)))
            return showPendingTransaction(withRef.clientRef, withRef)
        }
        upsertTransaction(saved)
        refreshAccounts()
        return saved
    }

    /**
     * Shows a transaction that's waiting to be sent: in the feed, in its account's balance and in this cycle's
     * purchasing power. The next sync replaces all of this with the server's figures.
     */
    fun showPendingTransaction(clientRef: String, input: TransactionInput): Transaction {
        val placeholder = Transaction(
            id = -(clientRef.hashCode().toLong().absoluteValue + 1), // negative: never a server id
            accountId = input.accountId,
            categoryId = input.categoryId,
            direction = input.direction,
            amount = input.amount,
            occurredOn = input.occurredOn,
            description = input.description,
            pendingSync = true,
        )
        if (_transactions.value.items.none { it.id == placeholder.id }) {
            upsertTransaction(placeholder)
            adjustBalance(input.accountId, placeholder.signedAmount)
            _dashboard.update { it?.withPending(placeholder) }
        }
        return placeholder
    }

    /** Moves an account's shown balance for a change not yet on the server. */
    fun adjustBalance(accountId: Long, delta: Money) {
        _accounts.update { list -> list.map { if (it.id == accountId) it.copy(balance = it.balance + delta) else it } }
    }

    /** Fills everything from a /sync snapshot (or the cached one). */
    fun applySnapshot(
        accounts: List<Account>,
        categories: List<Category>,
        incomeSources: List<IncomeSource>,
        transactions: TransactionPage,
        dashboard: Dashboard,
    ) {
        _accounts.value = accounts
        _categories.value = categories
        _incomeSources.value = incomeSources
        _transactions.value = TransactionFeed(transactions.data, transactions.meta.nextCursor, loaded = true)
        _dashboard.value = dashboard
    }

    private fun summary(input: TransactionInput): String =
        "${if (input.direction == Direction.CREDIT) "Income" else "Expense"} ${input.amount.formatInr()}" +
            (input.description?.let { " · $it" } ?: "") + " on ${input.occurredOn.formatShort()}"

    suspend fun confirmTransaction(id: Long): Transaction {
        val confirmed = api.confirmTransaction(id)
        upsertTransaction(confirmed)
        return confirmed
    }

    suspend fun deleteTransaction(id: Long) {
        api.deleteTransaction(id)
        _transactions.update { feed -> feed.copy(items = feed.items.filterNot { it.id == id }) }
        refreshAccounts()
    }

    // --- income

    suspend fun saveIncomeSource(id: Long?, input: IncomeSourceInput): IncomeSource {
        val saved = if (id == null) api.createIncomeSource(input) else api.updateIncomeSource(id, input)
        _incomeSources.update { list -> (list.filterNot { it.id == saved.id } + saved).sortedBy { it.name.lowercase() } }
        return saved
    }

    suspend fun deleteIncomeSource(id: Long) {
        api.deleteIncomeSource(id)
        _incomeSources.update { list -> list.filterNot { it.id == id } }
    }

    suspend fun logIncomeCredit(sourceId: Long, input: IncomeCreditInput): Transaction {
        val transaction = api.logIncomeCredit(sourceId, input)
        upsertTransaction(transaction)
        refreshAccounts()
        refreshIncomeSources() // last_credited_period changed
        return transaction
    }

    private fun upsertTransaction(transaction: Transaction) {
        _transactions.update { feed ->
            feed.copy(items = (feed.items.filterNot { it.id == transaction.id } + transaction).sortedWith(transactionOrder))
        }
    }

    private fun clear() {
        _accounts.value = emptyList()
        _categories.value = emptyList()
        _incomeSources.value = emptyList()
        _transactions.value = TransactionFeed()
        _dashboard.value = null
    }

    private companion object {
        val transactionOrder = compareByDescending<Transaction> { it.occurredOn }.thenByDescending { it.id }
        val categoryOrder = compareBy<Category> { it.kind }.thenBy { it.name.lowercase() }
    }
}
