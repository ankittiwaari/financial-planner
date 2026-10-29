package com.algopraxis.dhanniti.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.Transaction
import com.algopraxis.dhanniti.domain.today
import com.algopraxis.dhanniti.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

enum class TransactionFilter(val label: String, val direction: Direction?) {
    ALL("All", null),
    MONEY_OUT("Money out", Direction.DEBIT),
    MONEY_IN("Money in", Direction.CREDIT),
}

data class TransactionsUiState(
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val filter: TransactionFilter = TransactionFilter.ALL,
    val groups: List<Pair<LocalDate, List<Transaction>>> = emptyList(),
    val hasMore: Boolean = false,
    val categories: Map<Long, Category> = emptyMap(),
    val accountNames: Map<Long, String> = emptyMap(),
    val today: LocalDate = today(),
)

private data class ListStatus(
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val filter: TransactionFilter = TransactionFilter.ALL,
)

class TransactionsViewModel(private val ledger: LedgerRepository) : ViewModel() {
    private val status = MutableStateFlow(ListStatus())

    val state: StateFlow<TransactionsUiState> = combine(
        ledger.transactions,
        ledger.categories,
        ledger.accounts,
        status,
    ) { feed, categories, accounts, status ->
        // Filtering is over the pages loaded so far; "Load more" fetches older ones.
        val visible = feed.items.filter { status.filter.direction == null || it.direction == status.filter.direction }
        TransactionsUiState(
            loaded = feed.loaded,
            refreshing = status.refreshing,
            loadingMore = status.loadingMore,
            error = status.error,
            filter = status.filter,
            groups = visible.groupBy { it.occurredOn }.toList(),
            hasMore = feed.hasMore,
            categories = categories.associateBy { it.id },
            accountNames = accounts.associate { it.id to it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    init {
        if (!ledger.transactions.value.loaded) refresh()
    }

    fun setFilter(filter: TransactionFilter) = status.update { it.copy(filter = filter) }

    fun refresh() {
        status.update { it.copy(refreshing = true, error = null) }
        launchCatching(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(refreshing = false) } },
        ) {
            ledger.refreshAll()
        }
    }

    fun loadMore() {
        if (status.value.loadingMore) return
        status.update { it.copy(loadingMore = true, error = null) }
        launchCatching(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(loadingMore = false) } },
        ) {
            ledger.loadMoreTransactions()
        }
    }
}
