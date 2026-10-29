package com.algopraxis.dhanniti.ui.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.IncomeCreditInput
import com.algopraxis.dhanniti.data.api.IncomeSourceInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.IncomeSource
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.PayRule
import com.algopraxis.dhanniti.domain.WeekendShift
import com.algopraxis.dhanniti.domain.periodKey
import com.algopraxis.dhanniti.domain.startOfMonth
import com.algopraxis.dhanniti.domain.toInputString
import com.algopraxis.dhanniti.domain.today
import com.algopraxis.dhanniti.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The "Log credit" dialog for one income source. */
data class CreditDialogState(
    val source: IncomeSource,
    val periods: List<String>,
    val period: String,
    val amount: String,
    val saving: Boolean = false,
    val error: String? = null,
)

data class IncomeUiState(
    val sources: List<IncomeSource> = emptyList(),
    val accountNames: Map<Long, String> = emptyMap(),
    val hasAccounts: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val dialog: CreditDialogState? = null,
    val message: String? = null,
    val today: LocalDate = today(),
)

private data class IncomeStatus(
    val refreshing: Boolean = false,
    val error: String? = null,
    val dialog: CreditDialogState? = null,
    val message: String? = null,
)

class IncomeViewModel(private val ledger: LedgerRepository) : ViewModel() {
    private val status = MutableStateFlow(IncomeStatus())

    val state: StateFlow<IncomeUiState> = combine(ledger.incomeSources, ledger.accounts, status) { sources, accounts, status ->
        IncomeUiState(
            sources = sources,
            accountNames = accounts.associate { it.id to it.name },
            hasAccounts = accounts.any { !it.archived },
            refreshing = status.refreshing,
            error = status.error,
            dialog = status.dialog,
            message = status.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IncomeUiState())

    init {
        refresh()
    }

    fun refresh() {
        status.update { it.copy(refreshing = true, error = null) }
        launchCatching(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(refreshing = false) } },
        ) {
            ledger.refreshIncomeSources()
            ledger.refreshAccounts()
        }
    }

    fun openCreditDialog(source: IncomeSource) {
        val periods = creditPeriodChoices(today())
        status.update {
            it.copy(
                message = null,
                dialog = CreditDialogState(
                    source = source,
                    periods = periods,
                    period = defaultCreditPeriod(periods, source.lastCreditedPeriod),
                    amount = source.amount.toInputString(),
                ),
            )
        }
    }

    fun dismissDialog() = status.update { it.copy(dialog = null) }
    fun setDialogPeriod(period: String) = status.update { s -> s.copy(dialog = s.dialog?.copy(period = period, error = null)) }
    fun setDialogAmount(value: String) = status.update { s -> s.copy(dialog = s.dialog?.copy(amount = value, error = null)) }
    fun clearMessage() = status.update { it.copy(message = null) }

    fun confirmCredit() {
        val dialog = status.value.dialog ?: return
        if (dialog.saving) return
        val amount = Money.parse(dialog.amount)?.takeIf { it > Money.ZERO }
        if (amount == null) {
            status.update { it.copy(dialog = dialog.copy(error = "Enter an amount above ₹0")) }
            return
        }
        status.update { it.copy(dialog = dialog.copy(saving = true, error = null)) }
        launchCatching(onError = { message -> status.update { it.copy(dialog = it.dialog?.copy(saving = false, error = message)) } }) {
            ledger.logIncomeCredit(dialog.source.id, IncomeCreditInput(period = dialog.period, amount = amount))
            status.update { it.copy(dialog = null, message = "Logged ${dialog.source.name}") }
        }
    }

    companion object {
        /** Last month, this month, next month: covers a late log, today's, and an early credit. */
        fun creditPeriodChoices(today: LocalDate): List<String> {
            val month = today.startOfMonth()
            return listOf(month.minus(DatePeriod(months = 1)), month, month.plus(DatePeriod(months = 1))).map { it.periodKey() }
        }

        /** This month, unless it's already credited, then next month. Periods compare correctly as strings. */
        fun defaultCreditPeriod(choices: List<String>, lastCredited: String?): String {
            val thisMonth = choices[1]
            return if (lastCredited != null && lastCredited >= thisMonth) choices[2] else thisMonth
        }
    }
}

data class IncomeEditorUiState(
    val isNew: Boolean,
    val name: String = "",
    val amount: String = "",
    val isVariable: Boolean = false,
    val accountId: Long? = null,
    val payRule: PayRule = PayRule.LAST_WORKING_DAY,
    val payDay: String = "1",
    val weekendShift: WeekendShift = WeekendShift.BEFORE,
    val autoCredit: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val fieldErrors: Set<String> = emptySet(),
    val done: Boolean = false,
)

class IncomeEditorViewModel(private val id: Long, private val ledger: LedgerRepository) : ViewModel() {
    private val form = MutableStateFlow(initialState())

    val state: StateFlow<IncomeEditorUiState> = combine(form, ledger.accounts) { form, accounts ->
        val active = accounts.filter { !it.archived || it.id == form.accountId }
        form.copy(
            accounts = active,
            accountId = form.accountId ?: active.firstOrNull { it.isPrimary }?.id ?: active.firstOrNull()?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    private fun initialState(): IncomeEditorUiState {
        if (id == 0L) return IncomeEditorUiState(isNew = true, name = "Salary")
        val source = ledger.incomeSources.value.firstOrNull { it.id == id }
            ?: return IncomeEditorUiState(isNew = false, error = "This income source is no longer loaded. Go back and refresh.")
        return IncomeEditorUiState(
            isNew = false,
            name = source.name,
            amount = source.amount.toInputString(),
            isVariable = source.isVariable,
            accountId = source.accountId,
            payRule = source.payRule,
            payDay = (source.payDay ?: 1).toString(),
            weekendShift = source.weekendShift,
            autoCredit = source.autoCredit,
        )
    }

    private fun edit(field: String? = null, change: (IncomeEditorUiState) -> IncomeEditorUiState) =
        form.update { change(it).copy(fieldErrors = it.fieldErrors - setOfNotNull(field)) }

    fun setName(value: String) = edit("name") { it.copy(name = value.take(60)) }
    fun setAmount(value: String) = edit("amount") { it.copy(amount = value) }
    fun setVariable(value: Boolean) = edit { it.copy(isVariable = value) }
    fun setAccount(id: Long) = edit { it.copy(accountId = id) }
    fun setPayRule(rule: PayRule) = edit { it.copy(payRule = rule) }
    fun setPayDay(value: String) = edit("payDay") { it.copy(payDay = value.filter(Char::isDigit).take(2)) }
    fun setWeekendShift(shift: WeekendShift) = edit { it.copy(weekendShift = shift) }
    fun setAutoCredit(value: Boolean) = edit { it.copy(autoCredit = value) }

    fun save() {
        val current = state.value
        if (current.saving) return
        val name = current.name.trim()
        val amount = Money.parse(current.amount)?.takeIf { it > Money.ZERO }
        val payDay = current.payDay.toIntOrNull()?.takeIf { it in 1..31 }
        val errors = buildSet {
            if (name.isEmpty()) add("name")
            if (amount == null) add("amount")
            if (current.payRule == PayRule.FIXED_DAY && payDay == null) add("payDay")
        }
        val accountId = current.accountId
        if (errors.isNotEmpty() || accountId == null || amount == null) {
            form.update { it.copy(fieldErrors = errors) }
            return
        }
        val input = IncomeSourceInput(
            name = name,
            accountId = accountId,
            amount = amount,
            isVariable = current.isVariable,
            payRule = current.payRule,
            payDay = if (current.payRule == PayRule.FIXED_DAY) payDay else null,
            weekendShift = current.weekendShift,
            autoCredit = current.autoCredit,
        )
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            ledger.saveIncomeSource(if (current.isNew) null else id, input)
            form.update { it.copy(saving = false, done = true) }
        }
    }

    fun delete() {
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            ledger.deleteIncomeSource(id)
            form.update { it.copy(saving = false, done = true) }
        }
    }
}
