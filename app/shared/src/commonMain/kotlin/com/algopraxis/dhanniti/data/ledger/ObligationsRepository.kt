package com.algopraxis.dhanniti.data.ledger

import com.algopraxis.dhanniti.data.api.CreditCardInput
import com.algopraxis.dhanniti.data.api.LoanInput
import com.algopraxis.dhanniti.data.api.ObligationsApi
import com.algopraxis.dhanniti.data.api.PayDueInput
import com.algopraxis.dhanniti.data.api.RecurringPaymentInput
import com.algopraxis.dhanniti.data.api.StatementInput
import com.algopraxis.dhanniti.data.api.isNetworkError
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.data.sync.OutboxRepository
import com.algopraxis.dhanniti.data.sync.PendingChange
import com.algopraxis.dhanniti.domain.CreditCard
import com.algopraxis.dhanniti.domain.Due
import com.algopraxis.dhanniti.domain.DueSource
import com.algopraxis.dhanniti.domain.DueStatus
import com.algopraxis.dhanniti.domain.Loan
import com.algopraxis.dhanniti.domain.LoanScheduleRow
import com.algopraxis.dhanniti.domain.RecurringPayment
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-memory source of truth for loans, cards, recurring payments and dues. Editing any of them changes dues on the
 * server, so writes re-fetch dues; paying moves money, so it also refreshes the ledger.
 */
class ObligationsRepository(
    private val api: ObligationsApi,
    private val ledger: LedgerRepository,
    tokenStore: TokenStore,
    scope: CoroutineScope,
    private val outbox: OutboxRepository,
) {
    private val _loans = MutableStateFlow<List<Loan>>(emptyList())
    val loans: StateFlow<List<Loan>> = _loans.asStateFlow()

    private val _cards = MutableStateFlow<List<CreditCard>>(emptyList())
    val cards: StateFlow<List<CreditCard>> = _cards.asStateFlow()

    private val _recurring = MutableStateFlow<List<RecurringPayment>>(emptyList())
    val recurring: StateFlow<List<RecurringPayment>> = _recurring.asStateFlow()

    private val _dues = MutableStateFlow<List<Due>>(emptyList())
    val dues: StateFlow<List<Due>> = _dues.asStateFlow()

    private val _duesLoaded = MutableStateFlow(false)

    /** False until dues are first fetched, so an empty [dues] can be told apart from "not loaded yet". */
    val duesLoaded: StateFlow<Boolean> = _duesLoaded.asStateFlow()

    init {
        scope.launch { tokenStore.tokenFlow.collect { if (it == null) clear() } }
    }

    suspend fun refreshAll() = coroutineScope {
        launch { refreshLoans() }
        launch { refreshCards() }
        launch { refreshRecurring() }
        launch { refreshDues() }
    }

    suspend fun refreshLoans() {
        _loans.value = api.loans()
    }

    suspend fun refreshCards() {
        _cards.value = api.creditCards()
    }

    suspend fun refreshRecurring() {
        _recurring.value = api.recurringPayments()
    }

    suspend fun refreshDues() {
        _dues.value = api.dues()
        _duesLoaded.value = true
    }

    // --- loans

    suspend fun saveLoan(id: Long?, input: LoanInput): Loan {
        val saved = if (id == null) api.createLoan(input) else api.updateLoan(id, input)
        _loans.update { list -> (list.filterNot { it.id == saved.id } + saved).sortedWith(loanOrder) }
        refreshDues()
        return saved
    }

    suspend fun closeLoan(id: Long): Loan {
        val closed = api.closeLoan(id)
        _loans.update { list -> (list.filterNot { it.id == id } + closed).sortedWith(loanOrder) }
        refreshDues()
        return closed
    }

    suspend fun deleteLoan(id: Long) {
        api.deleteLoan(id)
        _loans.update { list -> list.filterNot { it.id == id } }
        refreshDues()
    }

    suspend fun loanSchedule(id: Long): List<LoanScheduleRow> = api.loanSchedule(id)

    // --- cards

    suspend fun saveCard(id: Long?, input: CreditCardInput): CreditCard {
        val saved = if (id == null) api.createCreditCard(input) else api.updateCreditCard(id, input)
        _cards.update { list -> (list.filterNot { it.id == saved.id } + saved).sortedBy { it.nickname.lowercase() } }
        refreshDues()
        return saved
    }

    suspend fun deleteCard(id: Long) {
        api.deleteCreditCard(id)
        _cards.update { list -> list.filterNot { it.id == id } }
        refreshDues()
    }

    suspend fun enterStatement(cardId: Long, input: StatementInput) {
        api.enterStatement(cardId, input)
        refreshCards() // latest_statement may have changed
        refreshDues()
    }

    // --- recurring

    suspend fun saveRecurring(id: Long?, input: RecurringPaymentInput): RecurringPayment {
        val saved = if (id == null) api.createRecurringPayment(input) else api.updateRecurringPayment(id, input)
        _recurring.update { list -> (list.filterNot { it.id == saved.id } + saved).sortedBy { it.name.lowercase() } }
        refreshDues()
        return saved
    }

    suspend fun deleteRecurring(id: Long) {
        api.deleteRecurringPayment(id)
        _recurring.update { list -> list.filterNot { it.id == id } }
        refreshDues()
    }

    // --- dues

    /** Queued if the server can't be reached, and shown as paid meanwhile. */
    suspend fun payDue(id: Long, input: PayDueInput): Due {
        val paid = try {
            api.payDue(id, input)
        } catch (e: Exception) {
            if (!e.isNetworkError()) throw e
            val due = _dues.value.first { it.id == id }
            val amount = input.amount ?: due.amount
            outbox.add(PendingChange.PayDue(id, input, "Paid ${due.title} ${amount.formatInr()}"))
            return showPendingPayment(id, input)
        }
        return afterDueChange(paid, movedMoney = true)
    }

    /** Shows a payment that's waiting to be sent: the due as paid and the money out of its account. */
    fun showPendingPayment(id: Long, input: PayDueInput): Due {
        val due = _dues.value.firstOrNull { it.id == id } ?: return Due.placeholder(id)
        if (!due.isOpen) return due
        val amount = input.amount ?: due.amount
        val paid = due.copy(status = DueStatus.PAID, paidOn = input.paidOn ?: today(), paidAmount = amount)
        _dues.update { list -> list.map { if (it.id == id) paid else it } }
        (input.accountId ?: due.accountId)?.let { ledger.adjustBalance(it, -amount) }
        return paid
    }

    /** Fills everything from a /sync snapshot (or the cached one). */
    fun applySnapshot(loans: List<Loan>, cards: List<CreditCard>, recurring: List<RecurringPayment>, dues: List<Due>) {
        _loans.value = loans
        _cards.value = cards
        _recurring.value = recurring
        _dues.value = dues
        _duesLoaded.value = true
    }

    suspend fun skipDue(id: Long): Due = afterDueChange(api.skipDue(id), movedMoney = false)

    /** Undoes a payment (its transaction is deleted on the server) or a skip. */
    suspend fun reopenDue(id: Long): Due {
        val wasPaid = _dues.value.firstOrNull { it.id == id }?.paidAmount != null
        return afterDueChange(api.reopenDue(id), movedMoney = wasPaid)
    }

    private suspend fun afterDueChange(due: Due, movedMoney: Boolean): Due = coroutineScope {
        _dues.update { list -> list.map { if (it.id == due.id) due else it } }
        if (movedMoney) {
            launch { ledger.refreshAccounts() }
            launch { ledger.refreshTransactions() }
        }
        // Loan progress and card statements follow their dues.
        when (due.sourceType) {
            DueSource.LOAN -> launch { refreshLoans() }
            DueSource.CARD -> launch { refreshCards() }
            DueSource.RECURRING -> Unit
        }
        due
    }

    private fun clear() {
        _loans.value = emptyList()
        _cards.value = emptyList()
        _recurring.value = emptyList()
        _dues.value = emptyList()
        _duesLoaded.value = false
    }

    private companion object {
        val loanOrder = compareBy<Loan> { !it.isActive }.thenBy { it.name.lowercase() }
    }
}
