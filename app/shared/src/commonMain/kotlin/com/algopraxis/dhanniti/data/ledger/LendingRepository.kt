package com.algopraxis.dhanniti.data.ledger

import com.algopraxis.dhanniti.data.api.LendingApi
import com.algopraxis.dhanniti.data.api.LendingInput
import com.algopraxis.dhanniti.data.api.RepaymentInput
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.domain.Lending
import com.algopraxis.dhanniti.domain.Receivables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-memory source of truth for money lent. Every write changes the per-person summary, so it is re-fetched; writes
 * that book transactions (lending from or repaying into an account) also refresh the ledger.
 */
class LendingRepository(
    private val api: LendingApi,
    private val ledger: LedgerRepository,
    tokenStore: TokenStore,
    scope: CoroutineScope,
) {
    private val _lendings = MutableStateFlow<List<Lending>>(emptyList())
    val lendings: StateFlow<List<Lending>> = _lendings.asStateFlow()

    private val _summary = MutableStateFlow(Receivables())
    val summary: StateFlow<Receivables> = _summary.asStateFlow()

    init {
        scope.launch { tokenStore.tokenFlow.collect { if (it == null) clear() } }
    }

    suspend fun refreshAll() = coroutineScope {
        launch { _lendings.value = api.lendings() }
        launch { refreshSummary() }
    }

    /** Fills everything from a /sync snapshot (or the cached one). */
    fun applySnapshot(lendings: List<Lending>, summary: Receivables) {
        _lendings.value = lendings
        _summary.value = summary
    }

    suspend fun refreshSummary() {
        _summary.value = api.summary()
    }

    suspend fun save(id: Long?, input: LendingInput): Lending {
        val saved = if (id == null) api.createLending(input) else api.updateLending(id, input)
        afterChange(saved, movedMoney = true)
        return saved
    }

    suspend fun delete(id: Long) {
        api.deleteLending(id)
        _lendings.update { list -> list.filterNot { it.id == id } }
        afterChange(null, movedMoney = true)
    }

    suspend fun repay(id: Long, input: RepaymentInput): Lending =
        api.repay(id, input).also { afterChange(it, movedMoney = input.accountId != null) }

    suspend fun deleteRepayment(id: Long, repaymentId: Long): Lending {
        val hadTransaction = _lendings.value.firstOrNull { it.id == id }
            ?.repayments?.firstOrNull { it.id == repaymentId }?.transactionId != null
        return api.deleteRepayment(id, repaymentId).also { afterChange(it, movedMoney = hadTransaction) }
    }

    suspend fun writeOff(id: Long): Lending = api.writeOff(id).also { afterChange(it, movedMoney = false) }

    suspend fun reopen(id: Long): Lending = api.reopen(id).also { afterChange(it, movedMoney = false) }

    private suspend fun afterChange(lending: Lending?, movedMoney: Boolean) = coroutineScope {
        if (lending != null) {
            _lendings.update { list -> (list.filterNot { it.id == lending.id } + lending).sortedWith(order) }
        }
        launch { refreshSummary() }
        if (movedMoney) {
            launch { ledger.refreshAccounts() }
            launch { ledger.refreshTransactions() }
        }
    }

    private fun clear() {
        _lendings.value = emptyList()
        _summary.value = Receivables()
    }

    private companion object {
        /** Still owed first, then newest, as the server lists them. */
        val order = compareBy<Lending> { !it.isOutstanding }.thenByDescending { it.lentOn }.thenByDescending { it.id }
    }
}
