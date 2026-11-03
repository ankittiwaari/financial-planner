package com.algopraxis.dhanniti.ui.dues

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.PayDueInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.data.ledger.ObligationsRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Due
import com.algopraxis.dhanniti.domain.DueStatus
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.formatPeriod
import com.algopraxis.dhanniti.domain.formatShort
import com.algopraxis.dhanniti.domain.periodKey
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

enum class DueFilter(val label: String) {
    OPEN("To pay"),
    PAID("Paid"),
    SKIPPED("Skipped"),
}

data class DueGroup(val label: String, val dues: List<Due>)

/** The "Mark paid" dialog. */
data class PayDialogState(
    val due: Due,
    val amount: String,
    val accountId: Long?,
    val paidOn: LocalDate,
    val saving: Boolean = false,
    val error: String? = null,
)

/** A confirmation with an undo, shown after paying or skipping. */
data class DueMessage(val text: String, val undoDueId: Long?)

data class DuesUiState(
    val filter: DueFilter = DueFilter.OPEN,
    val groups: List<DueGroup> = emptyList(),
    val overdueTotal: Money = Money.ZERO,
    val next30Total: Money = Money.ZERO,
    val accounts: List<Account> = emptyList(),
    val accountNames: Map<Long, String> = emptyMap(),
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val dialog: PayDialogState? = null,
    val message: DueMessage? = null,
    val busyDueId: Long? = null,
    val today: LocalDate = today(),
)

private data class DuesStatus(
    val filter: DueFilter = DueFilter.OPEN,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val dialog: PayDialogState? = null,
    val message: DueMessage? = null,
    val busyDueId: Long? = null,
)

class DuesViewModel(
    private val obligations: ObligationsRepository,
    private val ledger: LedgerRepository,
) : ViewModel() {
    private val status = MutableStateFlow(DuesStatus())

    val state: StateFlow<DuesUiState> = combine(obligations.dues, ledger.accounts, status) { dues, accounts, status ->
        val today = today()
        val open = dues.filter { it.isOpen }
        DuesUiState(
            filter = status.filter,
            groups = groupDues(dues, status.filter, today),
            overdueTotal = open.filter { it.status == DueStatus.OVERDUE }.sumOf(),
            next30Total = open.filter { it.dueDate >= today && it.dueDate <= today.plus(DatePeriod(days = 30)) }.sumOf(),
            accounts = accounts.filterNot { it.archived },
            accountNames = accounts.associate { it.id to it.name },
            refreshing = status.refreshing,
            loaded = status.loaded,
            error = status.error,
            dialog = status.dialog,
            message = status.message,
            busyDueId = status.busyDueId,
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DuesUiState())

    init {
        refresh()
    }

    fun refresh() {
        status.update { it.copy(refreshing = true, error = null) }
        launchRefresh(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(refreshing = false, loaded = true) } },
        ) {
            obligations.refreshDues()
            ledger.refreshAccounts()
        }
    }

    fun setFilter(filter: DueFilter) = status.update { it.copy(filter = filter) }
    fun clearMessage() = status.update { it.copy(message = null) }

    fun openPayDialog(due: Due) {
        val accounts = state.value.accounts
        val accountId = due.accountId?.takeIf { id -> accounts.any { it.id == id } }
            ?: accounts.firstOrNull { it.isPrimary }?.id ?: accounts.firstOrNull()?.id
        status.update {
            it.copy(
                message = null,
                dialog = PayDialogState(
                    due = due,
                    amount = if (due.amount > Money.ZERO) due.amount.toInputString() else "",
                    accountId = accountId,
                    paidOn = today(),
                ),
            )
        }
    }

    fun dismissDialog() = status.update { it.copy(dialog = null) }
    fun setDialogAmount(value: String) = updateDialog { it.copy(amount = value, error = null) }
    fun setDialogAccount(id: Long) = updateDialog { it.copy(accountId = id, error = null) }
    fun setDialogDate(date: LocalDate) = updateDialog { it.copy(paidOn = date, error = null) }

    private fun updateDialog(change: (PayDialogState) -> PayDialogState) =
        status.update { s -> s.copy(dialog = s.dialog?.let(change)) }

    fun confirmPay() {
        val dialog = status.value.dialog ?: return
        if (dialog.saving) return
        val amount = Money.parse(dialog.amount)?.takeIf { it > Money.ZERO }
        if (amount == null) {
            updateDialog { it.copy(error = "Enter the amount you paid") }
            return
        }
        updateDialog { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> updateDialog { it.copy(saving = false, error = message) } }) {
            obligations.payDue(dialog.due.id, PayDueInput(amount = amount, paidOn = dialog.paidOn, accountId = dialog.accountId))
            status.update { it.copy(dialog = null, message = DueMessage("Paid ${dialog.due.title}", dialog.due.id)) }
        }
    }

    fun skip(due: Due) = act(due, "Skipped ${due.title}", undoable = true) { obligations.skipDue(due.id) }

    fun reopen(due: Due) = act(due, "Moved ${due.title} back to To pay", undoable = false) { obligations.reopenDue(due.id) }

    /** Undo from the confirmation message. */
    fun undo() {
        val id = status.value.message?.undoDueId ?: return
        val due = obligations.dues.value.firstOrNull { it.id == id } ?: return
        reopen(due)
    }

    private fun act(due: Due, message: String, undoable: Boolean, block: suspend () -> Unit) {
        if (status.value.busyDueId != null) return
        status.update { it.copy(busyDueId = due.id, message = null, error = null) }
        launchCatching(
            onError = { error -> status.update { it.copy(error = error) } },
            onFinally = { status.update { it.copy(busyDueId = null) } },
        ) {
            block()
            status.update { it.copy(message = DueMessage(message, due.id.takeIf { undoable })) }
        }
    }

    companion object {
        /**
         * Open dues: overdue first, then by week ("This week", "Next week", "Week of 16 Nov"). Paid and skipped:
         * newest first, by month.
         */
        fun groupDues(dues: List<Due>, filter: DueFilter, today: LocalDate): List<DueGroup> {
            val matching = dues.filter {
                when (filter) {
                    DueFilter.OPEN -> it.isOpen
                    DueFilter.PAID -> it.status == DueStatus.PAID
                    DueFilter.SKIPPED -> it.status == DueStatus.SKIPPED
                }
            }
            if (filter != DueFilter.OPEN) {
                return matching.sortedWith(compareByDescending<Due> { it.paidOn ?: it.dueDate }.thenByDescending { it.id })
                    .groupBy { formatPeriod((it.paidOn ?: it.dueDate).periodKey()) }
                    .map { (label, dues) -> DueGroup(label, dues) }
            }
            val thisWeek = today.weekStart()
            val (overdue, upcoming) = matching.sortedWith(compareBy<Due> { it.dueDate }.thenBy { it.id })
                .partition { it.status == DueStatus.OVERDUE }
            val weeks = upcoming.groupBy { it.dueDate.weekStart() }.map { (start, dues) ->
                val label = when (start) {
                    thisWeek -> "This week"
                    thisWeek.plus(DatePeriod(days = 7)) -> "Next week"
                    else -> "Week of ${start.formatShort().substringBeforeLast(' ')}"
                }
                DueGroup(label, dues)
            }
            return listOfNotNull(overdue.takeIf { it.isNotEmpty() }?.let { DueGroup("Overdue", it) }) + weeks
        }

        /** Monday of the date's week. */
        private fun LocalDate.weekStart(): LocalDate =
            minus(DatePeriod(days = dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber))

        private fun List<Due>.sumOf(): Money = fold(Money.ZERO) { sum, due -> sum + due.amount }
    }
}
