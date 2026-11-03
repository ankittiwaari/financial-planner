package com.algopraxis.dhanniti.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.isNetworkError
import com.algopraxis.dhanniti.data.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * For loading data that's also cached: runs [block], reporting only failures where the server answered. With no
 * connection the cached data stays on screen and the offline banner says why, so there's no error to show.
 */
internal fun ViewModel.launchRefresh(
    onError: (String) -> Unit,
    onFinally: () -> Unit = {},
    block: suspend () -> Unit,
): Job = launchCatching(onError = onError, onFinally = onFinally) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!e.isNetworkError()) throw e
    }
}

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
