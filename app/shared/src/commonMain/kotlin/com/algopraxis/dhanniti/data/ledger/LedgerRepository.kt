package com.algopraxis.dhanniti.data.ledger

import com.algopraxis.dhanniti.data.api.AccountInput
import com.algopraxis.dhanniti.data.api.CategoryInput
import com.algopraxis.dhanniti.data.api.IncomeCreditInput
import com.algopraxis.dhanniti.data.api.IncomeSourceInput
import com.algopraxis.dhanniti.data.api.LedgerApi
import com.algopraxis.dhanniti.data.api.TransactionInput
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.IncomeSource
import com.algopraxis.dhanniti.domain.LedgerSummary
import com.algopraxis.dhanniti.domain.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

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
 * money re-fetch accounts. Offline caching arrives in milestone 7.
 */
class LedgerRepository(
    private val api: LedgerApi,
    tokenStore: TokenStore,
    scope: CoroutineScope,
) {
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _incomeSources = MutableStateFlow<List<IncomeSource>>(emptyList())
    val incomeSources: StateFlow<List<IncomeSource>> = _incomeSources.asStateFlow()

    private val _transactions = MutableStateFlow(TransactionFeed())
    val transactions: StateFlow<TransactionFeed> = _transactions.asStateFlow()

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

    suspend fun saveTransaction(id: Long?, input: TransactionInput): Transaction {
        val saved = if (id == null) api.createTransaction(input) else api.updateTransaction(id, input)
        upsertTransaction(saved)
        refreshAccounts()
        return saved
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
    }

    private companion object {
        val transactionOrder = compareByDescending<Transaction> { it.occurredOn }.thenByDescending { it.id }
        val categoryOrder = compareBy<Category> { it.kind }.thenBy { it.name.lowercase() }
    }
}
