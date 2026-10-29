package com.algopraxis.dhanniti.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.ApiException
import com.algopraxis.dhanniti.data.auth.AuthRepository
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.IncomeSource
import com.algopraxis.dhanniti.domain.LedgerSummary
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.Transaction
import com.algopraxis.dhanniti.domain.User
import com.algopraxis.dhanniti.domain.endOfMonth
import com.algopraxis.dhanniti.domain.startOfMonth
import com.algopraxis.dhanniti.domain.today
import com.algopraxis.dhanniti.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
) {
    val totalBalance: Money get() = accounts.fold(Money.ZERO) { sum, account -> sum + account.balance }
}

private data class HomeStatus(val loading: Boolean = true, val error: String? = null, val summary: LedgerSummary? = null)

class HomeViewModel(
    private val authRepository: AuthRepository,
    private val ledger: LedgerRepository,
) : ViewModel() {
    private val status = MutableStateFlow(HomeStatus())

    val state: StateFlow<HomeUiState> = combine(
        authRepository.currentUser,
        ledger.accounts,
        ledger.transactions,
        combine(ledger.categories, ledger.incomeSources, ::Pair),
        status,
    ) { user, accounts, feed, (categories, incomeSources), status ->
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
        // Any change to the ledger changes this month's totals.
        viewModelScope.launch {
            ledger.transactions.map { it.items }.distinctUntilChanged().drop(1).collect { loadSummary() }
        }
    }

    fun refresh() {
        status.update { it.copy(loading = true, error = null) }
        launchCatching(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(loading = false) } },
        ) {
            try {
                authRepository.refreshProfile()
            } catch (e: ApiException.Unauthorized) {
                return@launchCatching // session cleared; App() shows login
            }
            ledger.refreshAll()
            loadSummary()
        }
    }

    private suspend fun loadSummary() {
        val today = today()
        val summary = runCatching { ledger.summary(today.startOfMonth(), today.endOfMonth()) }.getOrNull()
        status.update { it.copy(summary = summary ?: it.summary) }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
