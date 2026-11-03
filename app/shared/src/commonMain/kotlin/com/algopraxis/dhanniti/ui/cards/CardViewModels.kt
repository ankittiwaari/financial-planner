package com.algopraxis.dhanniti.ui.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.CreditCardInput
import com.algopraxis.dhanniti.data.api.StatementInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.data.ledger.ObligationsRepository
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.CreditCard
import com.algopraxis.dhanniti.domain.EstimateMode
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.StatementStatus
import com.algopraxis.dhanniti.domain.endOfMonth
import com.algopraxis.dhanniti.domain.periodKey
import com.algopraxis.dhanniti.domain.startOfMonth
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** The "Enter bill" dialog for one card. */
data class StatementDialogState(
    val card: CreditCard,
    val periods: List<String>,
    val period: String,
    val totalDue: String = "",
    val minDue: String = "",
    val saving: Boolean = false,
    val error: String? = null,
)

data class CardsUiState(
    val cards: List<CreditCard> = emptyList(),
    val accountNames: Map<Long, String> = emptyMap(),
    val hasAccounts: Boolean = true,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val dialog: StatementDialogState? = null,
    val message: String? = null,
    val today: LocalDate = today(),
)

private data class CardsStatus(
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val dialog: StatementDialogState? = null,
    val message: String? = null,
)

class CardsViewModel(private val obligations: ObligationsRepository, private val ledger: LedgerRepository) : ViewModel() {
    private val status = MutableStateFlow(CardsStatus())

    val state: StateFlow<CardsUiState> = combine(obligations.cards, ledger.accounts, status) { cards, accounts, status ->
        CardsUiState(
            cards = cards,
            accountNames = accounts.associate { it.id to it.name },
            hasAccounts = accounts.any { !it.archived },
            refreshing = status.refreshing,
            loaded = status.loaded,
            error = status.error,
            dialog = status.dialog,
            message = status.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardsUiState())

    init {
        refresh()
    }

    fun refresh() {
        status.update { it.copy(refreshing = true, error = null) }
        launchRefresh(
            onError = { message -> status.update { it.copy(error = message) } },
            onFinally = { status.update { it.copy(refreshing = false, loaded = true) } },
        ) {
            obligations.refreshCards()
            ledger.refreshAccounts()
        }
    }

    fun openStatementDialog(card: CreditCard) {
        val periods = statementPeriodChoices(today(), card.statementDay)
        // The latest statement is usually the one waiting for its real amount.
        val latest = card.latestStatement?.takeIf { it.period in periods }
        status.update {
            it.copy(
                message = null,
                dialog = StatementDialogState(
                    card = card,
                    periods = periods,
                    period = latest?.period ?: periods.first(),
                    totalDue = latest?.takeIf { it.status != StatementStatus.ESTIMATED }?.totalDue?.toInputString().orEmpty(),
                    minDue = latest?.minDue?.toInputString().orEmpty(),
                ),
            )
        }
    }

    fun dismissDialog() = status.update { it.copy(dialog = null) }
    fun setDialogPeriod(value: String) = status.update { s -> s.copy(dialog = s.dialog?.copy(period = value, error = null)) }
    fun setDialogTotal(value: String) = status.update { s -> s.copy(dialog = s.dialog?.copy(totalDue = value, error = null)) }
    fun setDialogMin(value: String) = status.update { s -> s.copy(dialog = s.dialog?.copy(minDue = value, error = null)) }
    fun clearMessage() = status.update { it.copy(message = null) }

    fun confirmStatement() {
        val dialog = status.value.dialog ?: return
        if (dialog.saving) return
        val total = Money.parse(dialog.totalDue)
        val min = if (dialog.minDue.isBlank()) null else Money.parse(dialog.minDue)
        val error = when {
            total == null -> "Enter the total amount due"
            dialog.minDue.isNotBlank() && min == null -> "Enter a valid minimum due"
            min != null && min > total -> "The minimum due can't be more than the total"
            else -> null
        }
        if (error != null || total == null) {
            status.update { it.copy(dialog = dialog.copy(error = error)) }
            return
        }
        status.update { it.copy(dialog = dialog.copy(saving = true, error = null)) }
        launchCatching(onError = { message -> status.update { it.copy(dialog = it.dialog?.copy(saving = false, error = message)) } }) {
            obligations.enterStatement(dialog.card.id, StatementInput(dialog.period, total, min))
            status.update { it.copy(dialog = null, message = "Saved the ${dialog.card.nickname} bill") }
        }
    }

    companion object {
        /** The latest statement month (this month once the statement day has come, else last month), and the two before it. */
        fun statementPeriodChoices(today: LocalDate, statementDay: Int): List<String> {
            val latest = if (today.day >= statementDay.coerceAtMost(today.endOfMonth().day)) today.startOfMonth() else today.startOfMonth().minus(DatePeriod(months = 1))
            return (0..2).map { latest.minus(DatePeriod(months = it)).periodKey() }
        }
    }
}

data class CardEditorUiState(
    val isNew: Boolean,
    val nickname: String = "",
    val last4: String = "",
    val issuer: String = "",
    val creditLimit: String = "",
    val statementDay: String = "",
    val dueDay: String = "",
    val accountId: Long? = null,
    val estimateMode: EstimateMode = EstimateMode.LAST_STATEMENT,
    val fixedEstimate: String = "",
    val accounts: List<Account> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val fieldErrors: Set<String> = emptySet(),
    val done: Boolean = false,
)

class CardEditorViewModel(
    private val id: Long,
    private val obligations: ObligationsRepository,
    ledger: LedgerRepository,
) : ViewModel() {
    private val form = MutableStateFlow(initialState())

    val state: StateFlow<CardEditorUiState> = combine(form, ledger.accounts) { form, accounts ->
        val active = accounts.filter { !it.archived || it.id == form.accountId }
        form.copy(
            accounts = active,
            accountId = form.accountId ?: active.firstOrNull { it.isPrimary }?.id ?: active.firstOrNull()?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value)

    private fun initialState(): CardEditorUiState {
        if (id == 0L) return CardEditorUiState(isNew = true)
        val card = obligations.cards.value.firstOrNull { it.id == id }
            ?: return CardEditorUiState(isNew = false, error = "This card is no longer loaded. Go back and refresh.")
        return CardEditorUiState(
            isNew = false,
            nickname = card.nickname,
            last4 = card.last4.orEmpty(),
            issuer = card.issuer.orEmpty(),
            creditLimit = card.creditLimit?.toInputString().orEmpty(),
            statementDay = card.statementDay.toString(),
            dueDay = card.dueDay.toString(),
            accountId = card.payFromAccountId,
            estimateMode = card.estimateMode,
            fixedEstimate = card.fixedEstimate?.toInputString().orEmpty(),
        )
    }

    private fun edit(field: String? = null, change: (CardEditorUiState) -> CardEditorUiState) =
        form.update { change(it).copy(fieldErrors = it.fieldErrors - setOfNotNull(field)) }

    fun setNickname(value: String) = edit("nickname") { it.copy(nickname = value.take(40)) }
    fun setLast4(value: String) = edit("last4") { it.copy(last4 = value.filter(Char::isDigit).take(4)) }
    fun setIssuer(value: String) = edit { it.copy(issuer = value.take(40)) }
    fun setCreditLimit(value: String) = edit("creditLimit") { it.copy(creditLimit = value) }
    fun setStatementDay(value: String) = edit("statementDay") { it.copy(statementDay = value) }
    fun setDueDay(value: String) = edit("dueDay") { it.copy(dueDay = value) }
    fun setAccount(id: Long) = edit { it.copy(accountId = id) }
    fun setEstimateMode(value: EstimateMode) = edit { it.copy(estimateMode = value) }
    fun setFixedEstimate(value: String) = edit("fixedEstimate") { it.copy(fixedEstimate = value) }

    fun save() {
        val current = state.value
        if (current.saving) return
        val nickname = current.nickname.trim()
        val statementDay = current.statementDay.toIntOrNull()?.takeIf { it in 1..31 }
        val dueDay = current.dueDay.toIntOrNull()?.takeIf { it in 1..31 }
        val limit = if (current.creditLimit.isBlank()) null else Money.parse(current.creditLimit)?.takeIf { it > Money.ZERO }
        val fixed = if (current.estimateMode == EstimateMode.FIXED) Money.parse(current.fixedEstimate) else null
        val errors = buildSet {
            if (nickname.isEmpty()) add("nickname")
            if (current.last4.isNotEmpty() && current.last4.length != 4) add("last4")
            if (statementDay == null) add("statementDay")
            if (dueDay == null) add("dueDay")
            if (current.creditLimit.isNotBlank() && limit == null) add("creditLimit")
            if (current.estimateMode == EstimateMode.FIXED && fixed == null) add("fixedEstimate")
        }
        val accountId = current.accountId
        if (errors.isNotEmpty() || accountId == null || statementDay == null || dueDay == null) {
            form.update { it.copy(fieldErrors = errors) }
            return
        }
        val input = CreditCardInput(
            nickname = nickname,
            last4 = current.last4.ifEmpty { null },
            issuer = current.issuer.trim().ifEmpty { null },
            creditLimit = limit,
            statementDay = statementDay,
            dueDay = dueDay,
            payFromAccountId = accountId,
            estimateMode = current.estimateMode,
            fixedEstimate = fixed,
        )
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            obligations.saveCard(if (current.isNew) null else id, input)
            form.update { it.copy(saving = false, done = true) }
        }
    }

    fun delete() {
        form.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> form.update { it.copy(saving = false, error = message) } }) {
            obligations.deleteCard(id)
            form.update { it.copy(saving = false, done = true) }
        }
    }
}
