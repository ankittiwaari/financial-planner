package com.algopraxis.dhanniti.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Runs [block], reporting any failure (except cancellation) as a user-facing message. */
internal fun ViewModel.launchCatching(
    onError: (String) -> Unit,
    onFinally: () -> Unit = {},
    block: suspend () -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e.userMessage())
    } finally {
        onFinally()
    }
}
