package com.algopraxis.dhanniti.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.TransactionInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.today
import com.algopraxis.dhanniti.domain.toInputString
import com.algopraxis.dhanniti.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

data class TransactionForm(
    val direction: Direction,
    val amount: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val date: LocalDate = today(),
    val note: String = "",
    val isAuto: Boolean = false,
    /** Set when this is an income source's credit for a pay period. */
    val period: String? = null,
)

data class TransactionEditorUiState(
    val isNew: Boolean,
    val form: TransactionForm,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val showAmountError: Boolean = false,
    val done: Boolean = false,
) {
    val amount: Money? get() = Money.parse(form.amount)?.takeIf { it > Money.ZERO }
    val canSave: Boolean get() = !saving && form.accountId != null
}

private data class EditorStatus(
    val saving: Boolean = false,
    val error: String? = null,
    val showAmountError: Boolean = false,
    val done: Boolean = false,
)

class TransactionEditorViewModel(
    private val id: Long,
    initialDirection: Direction,
    private val ledger: LedgerRepository,
) : ViewModel() {
    private val isNew = id == 0L
    private val status = MutableStateFlow(EditorStatus()) // before form: initialForm() may set an error
    private val form = MutableStateFlow(initialForm(initialDirection))

    val state: StateFlow<TransactionEditorUiState> = combine(form, ledger.accounts, ledger.categories, status) { form, accounts, categories, status ->
        TransactionEditorUiState(
            isNew = isNew,
            form = form.copy(accountId = form.accountId ?: accounts.firstOrNull { it.isPrimary }?.id ?: accounts.firstOrNull()?.id),
            // Archived accounts can't take new entries, but an existing entry keeps showing its own account.
            accounts = accounts.filter { !it.archived || it.id == form.accountId },
            categories = categories.filter { it.kind == form.direction.categoryKind },
            saving = status.saving,
            error = status.error,
            showAmountError = status.showAmountError,
            done = status.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionEditorUiState(isNew, form.value))

    private fun initialForm(direction: Direction): TransactionForm {
        if (isNew) return TransactionForm(direction = direction)
        val existing = ledger.transactions.value.items.firstOrNull { it.id == id }
            ?: return TransactionForm(direction = direction).also {
                status.value = EditorStatus(error = "This transaction is no longer loaded. Go back and refresh.")
            }
        return TransactionForm(
            direction = existing.direction,
            amount = existing.amount.toInputString(),
            categoryId = existing.categoryId,
            accountId = existing.accountId,
            date = existing.occurredOn,
            note = existing.description.orEmpty(),
            isAuto = existing.isAuto,
            period = existing.period,
        )
    }

    fun setDirection(direction: Direction) = form.update {
        if (it.direction == direction) it else it.copy(direction = direction, categoryId = null)
    }

    fun setAmount(value: String) {
        form.update { it.copy(amount = value) }
        status.update { it.copy(showAmountError = false) }
    }

    fun setCategory(id: Long) = form.update { it.copy(categoryId = if (it.categoryId == id) null else id) }
    fun setAccount(id: Long) = form.update { it.copy(accountId = id) }
    fun setDate(date: LocalDate) = form.update { it.copy(date = date) }
    fun setNote(value: String) = form.update { it.copy(note = value.take(200)) }

    fun save() {
        val current = state.value
        val amount = current.amount
        val accountId = current.form.accountId
        if (amount == null) {
            status.update { it.copy(showAmountError = true) }
            return
        }
        if (accountId == null || current.saving) return

        val input = TransactionInput(
            accountId = accountId,
            categoryId = current.form.categoryId,
            direction = current.form.direction,
            amount = amount,
            occurredOn = current.form.date,
            description = current.form.note.trim().ifEmpty { null },
        )
        status.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> status.update { it.copy(saving = false, error = message) } }) {
            ledger.saveTransaction(if (isNew) null else id, input)
            status.update { it.copy(saving = false, done = true) }
        }
    }

    fun delete() {
        if (isNew) return
        status.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> status.update { it.copy(saving = false, error = message) } }) {
            ledger.deleteTransaction(id)
            status.update { it.copy(saving = false, done = true) }
        }
    }
}
