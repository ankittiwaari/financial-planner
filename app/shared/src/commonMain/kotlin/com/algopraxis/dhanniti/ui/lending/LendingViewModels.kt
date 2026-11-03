package com.algopraxis.dhanniti.ui.lending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.LendingInput
import com.algopraxis.dhanniti.data.api.RepaymentInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.data.ledger.LendingRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Lending
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.PersonBalance
import com.algopraxis.dhanniti.domain.Receivables
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.personKey
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

data class LentMoneyUiState(
    val receivables: Receivables = Receivables(),
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val today: LocalDate = today(),
) {
    val owing: List<PersonBalance> get() = receivables.people.filter { it.outstanding > Money.ZERO }
    val squared: List<PersonBalance> get() = receivables.people.filter { it.outstanding == Money.ZERO }
}

private data class ListStatus(val refreshing: Boolean = false, val loaded: Boolean = false, val error: String? = null)

/** Everyone you've lent to, with what each still owes. */
class LentMoneyViewModel(private val lending: LendingRepository) : ViewModel() {
    private val status = MutableStateFlow(ListStatus())

    val state: StateFlow<LentMoneyUiState> = combine(lending.summary, status) { summary, status ->
        LentMoneyUiState(summary, status.refreshing, status.loaded, status.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LentMoneyUiState())

    init {
        refresh()
    }

    fun refresh() {
        status.update { it.copy(refreshing = true, error = null) }
        launchRefresh(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(refreshing = false, loaded = true) } },
        ) {
            lending.refreshAll()
        }
    }
}

/** The "Record repayment" dialog for one lending. */
data class RepayDialogState(
    val lending: Lending,
    val amount: String,
    val receivedOn: LocalDate,
    /** Null: not into a tracked account. */
    val accountId: Long?,
    val saving: Boolean = false,
    val error: String? = null,
)

data class PersonUiState(
    val name: String,
    val balance: PersonBalance? = null,
    val lendings: List<Lending> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val accountNames: Map<Long, String> = emptyMap(),
    val dialog: RepayDialogState? = null,
    val busyId: Long? = null,
    val error: String? = null,
    val today: LocalDate = today(),
)

private data class PersonStatus(val dialog: RepayDialogState? = null, val busyId: Long? = null, val error: String? = null)

/** One person's lendings and repayments. [name] matches any spacing or case, as the server groups them. */
class PersonViewModel(
    private val name: String,
    private val lending: LendingRepository,
    ledger: LedgerRepository,
) : ViewModel() {
    private val status = MutableStateFlow(PersonStatus())
    private val key = personKey(name)

    val state: StateFlow<PersonUiState> = combine(lending.lendings, lending.summary, ledger.accounts, status) { lendings, summary, accounts, status ->
        val balance = summary.people.firstOrNull { personKey(it.name) == key }
        PersonUiState(
            name = balance?.name ?: name,
            balance = balance,
            lendings = lendings.filter { personKey(it.personName) == key },
            accounts = accounts.filterNot { it.archived },
            accountNames = accounts.associate { it.id to it.name },
            dialog = status.dialog,
            busyId = status.busyId,
            error = status.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonUiState(name))

    init {
        launchRefresh(onError = { message -> status.update { it.copy(error = message) } }) { lending.refreshAll() }
    }

    fun openRepayDialog(item: Lending) {
        val accounts = state.value.accounts
        status.update {
            it.copy(
                error = null,
                dialog = RepayDialogState(
                    lending = item,
                    amount = item.outstanding.toInputString(),
                    receivedOn = today(),
                    // Back into the account it came from, else the primary one.
                    accountId = item.accountId?.takeIf { id -> accounts.any { a -> a.id == id } }
                        ?: accounts.firstOrNull { a -> a.isPrimary }?.id,
                ),
            )
        }
    }

    fun dismissDialog() = status.update { it.copy(dialog = null) }
    fun setDialogAmount(value: String) = updateDialog { it.copy(amount = value, error = null) }
    fun setDialogDate(value: LocalDate) = updateDialog { it.copy(receivedOn = value) }
    fun setDialogAccount(id: Long?) = updateDialog { it.copy(accountId = id) }

    private fun updateDialog(change: (RepayDialogState) -> RepayDialogState) =
        status.update { s -> s.copy(dialog = s.dialog?.let(change)) }

    fun confirmRepay() {
        val dialog = status.value.dialog ?: return
        if (dialog.saving) return
        val amount = Money.parse(dialog.amount)?.takeIf { it > Money.ZERO }
        val error = when {
            amount == null -> "Enter the amount you got back"
            amount > dialog.lending.outstanding -> "Only ${dialog.lending.outstanding.formatInr()} is still owed on this"
            else -> null
        }
        if (error != null || amount == null) {
            updateDialog { it.copy(error = error) }
            return
        }
        updateDialog { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> updateDialog { it.copy(saving = false, error = message) } }) {
            lending.repay(dialog.lending.id, RepaymentInput(amount, dialog.receivedOn, dialog.accountId))
            status.update { it.copy(dialog = null) }
        }
    }

    fun deleteRepayment(item: Lending, repaymentId: Long) = act(item) { lending.deleteRepayment(item.id, repaymentId) }
    fun writeOff(item: Lending) = act(item) { lending.writeOff(item.id) }
    fun reopen(item: Lending) = act(item) { lending.reopen(item.id) }

    private fun act(item: Lending, block: suspend () -> Unit) {
        if (status.value.busyId != null) return
        status.update { it.copy(busyId = item.id, error = null) }
        launchCatching(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(busyId = null) } },
        ) { block() }
    }
}

data class LendingEditorUiState(
    val isNew: Boolean,
    val personName: String = "",
    val contact: String = "",
    val amount: String = "",
    val lentOn: LocalDate = today(),
    val expectedBy: LocalDate? = null,
    val notes: String = "",
    /** Null: not from a tracked account. */
    val accountId: Long? = null,
    val accounts: List<Account> = emptyList(),
    /** Names already used, so the same person is spelled the same way. */
    val knownPeople: List<String> = emptyList(),
    val repaid: Money = Money.ZERO,
    val saving: Boolean = false,
    val error: String? = null,
    val fieldErrors: Set<String> = emptySet(),
    val done: Boolean = false,
)

class LendingEditorViewModel(
    private val id: Long,
    personName: String,
    private val lending: LendingRepository,
    ledger: LedgerRepository,
) : ViewModel() {
    private val form = MutableStateFlow(initialState(personName, ledger.accounts.value))

    val state: StateFlow<LendingEditorUiState> = combine(form, ledger.accounts, lending.summary) { form, accounts, summary ->
        form.copy(
            accounts = accounts.filter { !it.archived || it.id == form.accountId },
            knownPeople = summary.people.map { it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    private fun initialState(personName: String, accounts: List<Account>): LendingEditorUiState {
        if (id == 0L) {
            return LendingEditorUiState(
                isNew = true,
                personName = personName,
                accountId = accounts.firstOrNull { it.isPrimary && !it.archived }?.id,
            )
        }
        val item = lending.lendings.value.firstOrNull { it.id == id }
            ?: return LendingEditorUiState(isNew = false, error = "This entry is no longer loaded. Go back and refresh.")
        return LendingEditorUiState(
            isNew = false,
            personName = item.personName,
            contact = item.contact.orEmpty(),
            amount = item.amount.toInputString(),
            lentOn = item.lentOn,
            expectedBy = item.expectedBy,
            notes = item.notes.orEmpty(),
            accountId = item.accountId,
            repaid = item.repaid,
        )
    }

    private fun edit(field: String? = null, change: (LendingEditorUiState) -> LendingEditorUiState) =
        form.update { change(it).copy(fieldErrors = it.fieldErrors - setOfNotNull(field)) }

    fun setPersonName(value: String) = edit("personName") { it.copy(personName = value.take(60)) }
    fun setContact(value: String) = edit { it.copy(contact = value.take(60)) }
    fun setAmount(value: String) = edit("amount") { it.copy(amount = value) }
    fun setLentOn(value: LocalDate) = edit("expectedBy") { it.copy(lentOn = value) }
    fun setExpectedBy(value: LocalDate?) = edit("expectedBy") { it.copy(expectedBy = value) }
    fun setNotes(value: String) = edit { it.copy(notes = value.take(200)) }
    fun setAccount(id: Long?) = edit { it.copy(accountId = id) }

    fun save() {
        val current = state.value
        if (current.saving) return
        val name = current.personName.trim()
        val amount = Money.parse(current.amount)?.takeIf { it > Money.ZERO }
        val errors = buildSet {
            if (name.isEmpty()) add("personName")
            if (amount == null || amount < current.repaid) add("amount")
            if (current.expectedBy != null && current.expectedBy < current.lentOn) add("expectedBy")
        }
        if (errors.isNotEmpty() || amount == null) {
            form.update { it.copy(fieldErrors = errors) }
            return
        }
        val input = LendingInput(
            personName = name,
            contact = current.contact.trim().ifEmpty { null },
            amount = amount,
            lentOn = current.lentOn,
            expectedBy = current.expectedBy,
            notes = current.notes.trim().ifEmpty { null },
            accountId = current.accountId,
        )
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            lending.save(if (current.isNew) null else id, input)
            form.update { it.copy(saving = false, done = true) }
        }
    }

    fun delete() {
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            lending.delete(id)
            form.update { it.copy(saving = false, done = true) }
        }
    }
}
