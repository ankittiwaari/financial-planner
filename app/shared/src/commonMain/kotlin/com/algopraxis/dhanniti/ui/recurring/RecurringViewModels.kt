package com.algopraxis.dhanniti.ui.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.RecurringPaymentInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.data.ledger.ObligationsRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.CategoryKind
import com.algopraxis.dhanniti.domain.Frequency
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.RecurringPayment
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

data class RecurringUiState(
    val payments: List<RecurringPayment> = emptyList(),
    val accountNames: Map<Long, String> = emptyMap(),
    val categories: Map<Long, Category> = emptyMap(),
    val hasAccounts: Boolean = true,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val today: LocalDate = today(),
) {
    /** What the payments cost in a typical month, quarterly and yearly ones spread out. */
    val monthlyTotal: Money get() = payments.filter { it.nextDate != null }.fold(Money.ZERO) { sum, it -> sum + Money(it.amount.paise / it.frequency.months) }
}

private data class RecurringStatus(val refreshing: Boolean = false, val loaded: Boolean = false, val error: String? = null)

class RecurringViewModel(private val obligations: ObligationsRepository, private val ledger: LedgerRepository) : ViewModel() {
    private val status = MutableStateFlow(RecurringStatus())

    val state: StateFlow<RecurringUiState> = combine(
        obligations.recurring,
        ledger.accounts,
        ledger.categories,
        status,
    ) { payments, accounts, categories, status ->
        RecurringUiState(
            payments = payments,
            accountNames = accounts.associate { it.id to it.name },
            categories = categories.associateBy { it.id },
            hasAccounts = accounts.any { !it.archived },
            refreshing = status.refreshing,
            loaded = status.loaded,
            error = status.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    init {
        refresh()
    }

    fun refresh() {
        status.update { it.copy(refreshing = true, error = null) }
        launchRefresh(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(refreshing = false, loaded = true) } },
        ) {
            obligations.refreshRecurring()
            ledger.refreshAccounts()
            ledger.refreshCategories()
        }
    }
}

data class RecurringEditorUiState(
    val isNew: Boolean,
    val name: String = "",
    val amount: String = "",
    val frequency: Frequency = Frequency.MONTHLY,
    /** The next (or first) payment; its day of the month repeats. */
    val nextDate: LocalDate = today(),
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val autoDebit: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val fieldErrors: Set<String> = emptySet(),
    val done: Boolean = false,
)

class RecurringEditorViewModel(
    private val id: Long,
    private val obligations: ObligationsRepository,
    ledger: LedgerRepository,
) : ViewModel() {
    private val form = MutableStateFlow(initialState())

    val state: StateFlow<RecurringEditorUiState> = combine(form, ledger.accounts, ledger.categories) { form, accounts, categories ->
        val active = accounts.filter { !it.archived || it.id == form.accountId }
        form.copy(
            accounts = active,
            accountId = form.accountId ?: active.firstOrNull { it.isPrimary }?.id ?: active.firstOrNull()?.id,
            categories = categories.filter { it.kind == CategoryKind.EXPENSE && (!it.archived || it.id == form.categoryId) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    private fun initialState(): RecurringEditorUiState {
        if (id == 0L) return RecurringEditorUiState(isNew = true)
        val payment = obligations.recurring.value.firstOrNull { it.id == id }
            ?: return RecurringEditorUiState(isNew = false, error = "This payment is no longer loaded. Go back and refresh.")
        return RecurringEditorUiState(
            isNew = false,
            name = payment.name,
            amount = payment.amount.toInputString(),
            frequency = payment.frequency,
            nextDate = payment.nextDate ?: payment.activeFrom,
            accountId = payment.accountId,
            categoryId = payment.categoryId,
            autoDebit = payment.autoDebit,
        )
    }

    private fun edit(field: String? = null, change: (RecurringEditorUiState) -> RecurringEditorUiState) =
        form.update { change(it).copy(fieldErrors = it.fieldErrors - setOfNotNull(field)) }

    fun setName(value: String) = edit("name") { it.copy(name = value.take(60)) }
    fun setAmount(value: String) = edit("amount") { it.copy(amount = value) }
    fun setFrequency(value: Frequency) = edit { it.copy(frequency = value) }
    fun setNextDate(value: LocalDate) = edit { it.copy(nextDate = value) }
    fun setAccount(id: Long) = edit { it.copy(accountId = id) }
    fun setCategory(id: Long?) = edit { it.copy(categoryId = id) }
    fun setAutoDebit(value: Boolean) = edit { it.copy(autoDebit = value) }

    fun save() {
        val current = state.value
        if (current.saving) return
        val name = current.name.trim()
        val amount = Money.parse(current.amount)?.takeIf { it > Money.ZERO }
        val errors = buildSet {
            if (name.isEmpty()) add("name")
            if (amount == null) add("amount")
        }
        val accountId = current.accountId
        if (errors.isNotEmpty() || accountId == null || amount == null) {
            form.update { it.copy(fieldErrors = errors) }
            return
        }
        val input = RecurringPaymentInput(
            name = name,
            amount = amount,
            frequency = current.frequency,
            day = current.nextDate.day,
            accountId = accountId,
            categoryId = current.categoryId,
            autoDebit = current.autoDebit,
            activeFrom = current.nextDate,
        )
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            obligations.saveRecurring(if (current.isNew) null else id, input)
            form.update { it.copy(saving = false, done = true) }
        }
    }

    fun delete() {
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            obligations.deleteRecurring(id)
            form.update { it.copy(saving = false, done = true) }
        }
    }
}
