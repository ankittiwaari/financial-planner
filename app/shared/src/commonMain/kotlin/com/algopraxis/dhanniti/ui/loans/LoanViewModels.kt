package com.algopraxis.dhanniti.ui.loans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.LoanInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.data.ledger.ObligationsRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Loan
import com.algopraxis.dhanniti.domain.LoanScheduleRow
import com.algopraxis.dhanniti.domain.LoanType
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.toInputString
import com.algopraxis.dhanniti.domain.today
import com.algopraxis.dhanniti.ui.launchCatching
import com.algopraxis.dhanniti.ui.launchRefresh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

data class LoansUiState(
    val loans: List<Loan> = emptyList(),
    val hasAccounts: Boolean = true,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val today: LocalDate = today(),
) {
    val totalOutstanding: Money get() = loans.filter { it.isActive }.fold(Money.ZERO) { sum, it -> sum + it.outstanding }
}

private data class ListStatus(val refreshing: Boolean = false, val loaded: Boolean = false, val error: String? = null)

class LoansViewModel(private val obligations: ObligationsRepository, private val ledger: LedgerRepository) : ViewModel() {
    private val status = MutableStateFlow(ListStatus())

    val state: StateFlow<LoansUiState> = combine(obligations.loans, ledger.accounts, status) { loans, accounts, status ->
        LoansUiState(loans, accounts.any { !it.archived }, status.refreshing, status.loaded, status.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoansUiState())

    init {
        refresh()
    }

    fun refresh() {
        status.update { it.copy(refreshing = true, error = null) }
        launchRefresh(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(refreshing = false, loaded = true) } },
        ) {
            obligations.refreshLoans()
            ledger.refreshAccounts()
        }
    }
}

data class LoanDetailUiState(
    val loan: Loan? = null,
    val accountName: String? = null,
    val schedule: List<LoanScheduleRow> = emptyList(),
    val loadingSchedule: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

private data class DetailStatus(
    val schedule: List<LoanScheduleRow> = emptyList(),
    val loadingSchedule: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

class LoanDetailViewModel(
    private val id: Long,
    private val obligations: ObligationsRepository,
    ledger: LedgerRepository,
) : ViewModel() {
    private val status = MutableStateFlow(DetailStatus())

    val state: StateFlow<LoanDetailUiState> = combine(obligations.loans, ledger.accounts, status) { loans, accounts, status ->
        val loan = loans.firstOrNull { it.id == id }
        LoanDetailUiState(
            loan = loan,
            accountName = loan?.let { l -> accounts.firstOrNull { it.id == l.accountId }?.name },
            schedule = status.schedule,
            loadingSchedule = status.loadingSchedule,
            busy = status.busy,
            error = status.error ?: if (loan == null && !status.done) "This loan is no longer loaded. Go back and refresh." else null,
            done = status.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoanDetailUiState())

    /** The screen calls this whenever the loan changes (first load, edits, EMIs paid). */
    fun loadSchedule() {
        status.update { it.copy(loadingSchedule = true) }
        launchCatching(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(loadingSchedule = false) } },
        ) {
            val schedule = obligations.loanSchedule(id)
            status.update { it.copy(schedule = schedule) }
        }
    }

    fun close() = perform { obligations.closeLoan(id) }

    fun delete() = perform {
        obligations.deleteLoan(id)
        status.update { it.copy(done = true) }
    }

    private fun perform(block: suspend () -> Unit) {
        if (status.value.busy) return
        status.update { it.copy(busy = true, error = null) }
        launchCatching(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(busy = false) } },
        ) { block() }
    }
}

data class LoanEditorUiState(
    val isNew: Boolean,
    val name: String = "",
    val lender: String = "",
    val type: LoanType = LoanType.HOME,
    val principal: String = "",
    val interestRate: String = "",
    val tenureMonths: String = "",
    val emiAmount: String = "",
    val firstEmiDate: LocalDate = today(),
    val accountId: Long? = null,
    val autoDebit: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val fieldErrors: Set<String> = emptySet(),
    val savedId: Long? = null,
)

class LoanEditorViewModel(
    private val id: Long,
    private val obligations: ObligationsRepository,
    ledger: LedgerRepository,
) : ViewModel() {
    private val form = MutableStateFlow(initialState())

    val state: StateFlow<LoanEditorUiState> = combine(form, ledger.accounts) { form, accounts ->
        val active = accounts.filter { !it.archived || it.id == form.accountId }
        form.copy(
            accounts = active,
            accountId = form.accountId ?: active.firstOrNull { it.isPrimary }?.id ?: active.firstOrNull()?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    private fun initialState(): LoanEditorUiState {
        if (id == 0L) return LoanEditorUiState(isNew = true)
        val loan = obligations.loans.value.firstOrNull { it.id == id }
            ?: return LoanEditorUiState(isNew = false, error = "This loan is no longer loaded. Go back and refresh.")
        return LoanEditorUiState(
            isNew = false,
            name = loan.name,
            lender = loan.lender.orEmpty(),
            type = loan.type,
            principal = loan.principal.toInputString(),
            interestRate = formatRate(loan.interestRate),
            tenureMonths = loan.tenureMonths.toString(),
            emiAmount = loan.emiAmount?.toInputString().orEmpty(),
            firstEmiDate = loan.firstEmiDate,
            accountId = loan.accountId,
            autoDebit = loan.autoDebit,
        )
    }

    private fun edit(field: String? = null, change: (LoanEditorUiState) -> LoanEditorUiState) =
        form.update { change(it).copy(fieldErrors = it.fieldErrors - setOfNotNull(field)) }

    fun setName(value: String) = edit("name") { it.copy(name = value.take(60)) }
    fun setLender(value: String) = edit { it.copy(lender = value.take(60)) }
    fun setType(value: LoanType) = edit { it.copy(type = value) }
    fun setPrincipal(value: String) = edit("principal") { it.copy(principal = value) }
    fun setInterestRate(value: String) = edit("interestRate") {
        it.copy(interestRate = value.filter { c -> c.isDigit() || c == '.' }.take(5))
    }
    fun setTenure(value: String) = edit("tenureMonths") { it.copy(tenureMonths = value) }
    fun setEmiAmount(value: String) = edit("emiAmount") { it.copy(emiAmount = value) }
    fun setFirstEmiDate(value: LocalDate) = edit { it.copy(firstEmiDate = value) }
    fun setAccount(id: Long) = edit { it.copy(accountId = id) }
    fun setAutoDebit(value: Boolean) = edit { it.copy(autoDebit = value) }

    fun save() {
        val current = state.value
        if (current.saving) return
        val name = current.name.trim()
        val principal = Money.parse(current.principal)?.takeIf { it > Money.ZERO }
        val rate = current.interestRate.toDoubleOrNull()?.takeIf { it in 0.0..99.99 }
        val tenure = current.tenureMonths.toIntOrNull()?.takeIf { it in 1..600 }
        val emi = if (current.emiAmount.isBlank()) null else Money.parse(current.emiAmount)?.takeIf { it > Money.ZERO }
        val errors = buildSet {
            if (name.isEmpty()) add("name")
            if (principal == null) add("principal")
            if (rate == null) add("interestRate")
            if (tenure == null) add("tenureMonths")
            if (current.emiAmount.isNotBlank() && emi == null) add("emiAmount")
        }
        val accountId = current.accountId
        if (errors.isNotEmpty() || accountId == null || principal == null || rate == null || tenure == null) {
            form.update { it.copy(fieldErrors = errors) }
            return
        }
        val input = LoanInput(
            name = name,
            lender = current.lender.trim().ifEmpty { null },
            type = current.type,
            principal = principal,
            interestRate = rate,
            tenureMonths = tenure,
            emiAmount = emi,
            firstEmiDate = current.firstEmiDate,
            accountId = accountId,
            autoDebit = current.autoDebit,
        )
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            val saved = obligations.saveLoan(if (current.isNew) null else id, input)
            form.update { it.copy(saving = false, savedId = saved.id) }
        }
    }

    private companion object {
        /** 8.5 → "8.5", 9.0 → "9". */
        fun formatRate(rate: Double): String = if (rate % 1.0 == 0.0) rate.toLong().toString() else rate.toString()
    }
}
