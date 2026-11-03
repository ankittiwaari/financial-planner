package com.algopraxis.dhanniti.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.ApiException
import com.algopraxis.dhanniti.data.auth.AuthRepository
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.data.ledger.LendingRepository
import com.algopraxis.dhanniti.data.ledger.ObligationsRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.Dashboard
import com.algopraxis.dhanniti.domain.IncomeSource
import com.algopraxis.dhanniti.domain.LedgerSummary
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.Receivables
import com.algopraxis.dhanniti.domain.Transaction
import com.algopraxis.dhanniti.domain.User
import com.algopraxis.dhanniti.domain.endOfMonth
import com.algopraxis.dhanniti.domain.startOfMonth
import com.algopraxis.dhanniti.domain.today
import com.algopraxis.dhanniti.ui.launchCatching
import com.algopraxis.dhanniti.ui.launchRefresh
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class HomeUiState(
    val user: User? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val today: LocalDate = today(),
    val accounts: List<Account> = emptyList(),
    val monthSummary: LedgerSummary? = null,
    val recent: List<Transaction> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val accountNames: Map<Long, String> = emptyMap(),
    val nextIncome: IncomeSource? = null,
    /** Money lent and still owed: shown, never counted as spendable. */
    val receivables: Receivables = Receivables(),
    /** Purchasing power for the current cycle; null until loaded. */
    val dashboard: Dashboard? = null,
) {
    val totalBalance: Money get() = accounts.fold(Money.ZERO) { sum, account -> sum + account.balance }
}

private data class HomeStatus(
    val loading: Boolean = true,
    val error: String? = null,
    val summary: LedgerSummary? = null,
)

private data class HomeExtras(
    val categories: List<Category>,
    val incomeSources: List<IncomeSource>,
    val receivables: Receivables,
    val dashboard: Dashboard?,
)

@OptIn(FlowPreview::class) // debounce
class HomeViewModel(
    private val authRepository: AuthRepository,
    private val ledger: LedgerRepository,
    private val lending: LendingRepository,
    private val obligations: ObligationsRepository,
) : ViewModel() {
    private val status = MutableStateFlow(HomeStatus())

    val state: StateFlow<HomeUiState> = combine(
        authRepository.currentUser,
        ledger.accounts,
        ledger.transactions,
        combine(ledger.categories, ledger.incomeSources, lending.summary, ledger.dashboard, ::HomeExtras),
        status,
    ) { user, accounts, feed, (categories, incomeSources, receivables, dashboard), status ->
        HomeUiState(
            user = user,
            loading = status.loading,
            error = status.error,
            accounts = accounts,
            monthSummary = status.summary,
            recent = feed.items.take(5),
            categories = categories.associateBy { it.id },
            accountNames = accounts.associate { it.id to it.name },
            nextIncome = incomeSources.filter { it.nextPayDate != null }.minByOrNull { it.nextPayDate!! },
            receivables = receivables,
            dashboard = dashboard,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
        // Any change to the ledger, dues or lending changes this month's totals and the purchasing power.
        viewModelScope.launch {
            merge(
                ledger.transactions.map { it.items }.distinctUntilChanged().drop(1),
                obligations.dues.drop(1),
                lending.summary.drop(1),
                ledger.incomeSources.drop(1),
            ).debounce(300).collect { loadSummary() }
        }
    }

    fun refresh() {
        status.update { it.copy(loading = true, error = null) }
        launchRefresh(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(loading = false) } },
        ) {
            try {
                authRepository.refreshProfile()
            } catch (e: ApiException.Unauthorized) {
                return@launchRefresh // session cleared; App() shows login
            }
            ledger.refreshAll()
            runCatching { lending.refreshSummary() } // the home screen still works without it
            loadSummary()
        }
    }

    /** Month totals and the dashboard. A failure keeps what was shown before rather than blanking the screen. */
    private suspend fun loadSummary() = coroutineScope {
        val today = today()
        launch {
            val summary = runCatching { ledger.summary(today.startOfMonth(), today.endOfMonth()) }.getOrNull()
            status.update { it.copy(summary = summary ?: it.summary) }
        }
        launch { runCatching { ledger.refreshDashboard() } }
    }

    /** Accepts an automatic entry from the "To do" card; the dashboard reloads as the ledger changes. */
    fun confirm(transactionId: Long) {
        launchCatching(onError = { message -> status.update { it.copy(error = message) } }) {
            ledger.confirmTransaction(transactionId)
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
