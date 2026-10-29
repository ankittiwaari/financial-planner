package com.algopraxis.dhanniti.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.AccountInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.AccountType
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.toInputString
import com.algopraxis.dhanniti.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class AccountsUiState(
    val accounts: List<Account> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    val total: Money get() = accounts.fold(Money.ZERO) { sum, it -> sum + it.balance }
}

class AccountsViewModel(private val ledger: LedgerRepository) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<AccountsUiState> = combine(ledger.accounts, refreshing, error) { accounts, refreshing, error ->
        AccountsUiState(accounts.filterNot { it.archived }, refreshing, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    init {
        refresh()
    }

    fun refresh() {
        refreshing.value = true
        error.value = null
        launchCatching(onError = { error.value = it }, onFinally = { refreshing.value = false }) {
            ledger.refreshAccounts()
        }
    }
}

data class AccountEditorUiState(
    val isNew: Boolean,
    val name: String = "",
    val type: AccountType = AccountType.BANK,
    val openingBalance: String = "",
    val isPrimary: Boolean = false,
    val currentBalance: Money? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val showNameError: Boolean = false,
    val showBalanceError: Boolean = false,
    val done: Boolean = false,
)

class AccountEditorViewModel(private val id: Long, private val ledger: LedgerRepository) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AccountEditorUiState> = _state.asStateFlow()

    private fun initialState(): AccountEditorUiState {
        if (id == 0L) return AccountEditorUiState(isNew = true, isPrimary = ledger.accounts.value.none { !it.archived })
        val account = ledger.accounts.value.firstOrNull { it.id == id }
            ?: return AccountEditorUiState(isNew = false, error = "This account is no longer loaded. Go back and refresh.")
        return AccountEditorUiState(
            isNew = false,
            name = account.name,
            type = account.type,
            openingBalance = account.openingBalance.toSignedInput(),
            isPrimary = account.isPrimary,
            currentBalance = account.balance,
        )
    }

    fun setName(value: String) = _state.update { it.copy(name = value.take(60), showNameError = false) }
    fun setType(type: AccountType) = _state.update { it.copy(type = type) }
    fun setOpeningBalance(value: String) = _state.update { it.copy(openingBalance = value, showBalanceError = false) }
    fun setPrimary(value: Boolean) = _state.update { it.copy(isPrimary = value) }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val name = current.name.trim()
        val opening = parseSigned(current.openingBalance)
        if (name.isEmpty() || opening == null) {
            _state.update { it.copy(showNameError = name.isEmpty(), showBalanceError = opening == null) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> _state.update { it.copy(saving = false, error = message) } }) {
            ledger.saveAccount(if (current.isNew) null else id, AccountInput(name, current.type, opening, current.isPrimary))
            _state.update { it.copy(saving = false, done = true) }
        }
    }

    fun archive() {
        _state.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> _state.update { it.copy(saving = false, error = message) } }) {
            ledger.archiveAccount(id)
            _state.update { it.copy(saving = false, done = true) }
        }
    }

    private companion object {
        /** Opening balance may be negative (an overdrawn account). Blank means zero. */
        fun parseSigned(text: String): Money? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return Money.ZERO
            val negative = trimmed.startsWith("-")
            val value = Money.parse(trimmed.removePrefix("-")) ?: return null
            return if (negative) -value else value
        }

        fun Money.toSignedInput(): String = if (isNegative) "-" + (-this).toInputString() else toInputString()
    }
}
