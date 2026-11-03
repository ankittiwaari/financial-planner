package com.algopraxis.dhanniti.ui.dues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.domain.Due
import com.algopraxis.dhanniti.domain.DueSource
import com.algopraxis.dhanniti.domain.DueStatus
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.domain.toInputString
import com.algopraxis.dhanniti.ui.components.AmountField
import com.algopraxis.dhanniti.ui.components.ChoiceChips
import com.algopraxis.dhanniti.ui.components.DateField
import com.algopraxis.dhanniti.ui.components.EmptyState
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.SectionLabel
import com.algopraxis.dhanniti.ui.components.TabScaffold
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuesScreen(
    onOpenLoans: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenReminders: () -> Unit,
    viewModel: DuesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TabScaffold(
        title = "Dues",
        actions = { TextButton(onClick = onOpenReminders) { Text("🔔 Reminders") } },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = onOpenLoans, label = { Text("🏦 Loans") })
                        AssistChip(onClick = onOpenCards, label = { Text("💳 Cards") })
                        AssistChip(onClick = onOpenRecurring, label = { Text("🔁 Recurring") })
                    }
                }
                item { Totals(state.overdueTotal, state.next30Total) }
                item { ChoiceChips(DueFilter.entries, state.filter, viewModel::setFilter, label = { it.label }) }
                state.error?.let { item { ErrorText(it) } }
                state.message?.let { message ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✓ ${message.text}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            if (message.undoDueId != null) {
                                TextButton(onClick = viewModel::undo, enabled = state.busyDueId == null) { Text("Undo") }
                            }
                            TextButton(onClick = viewModel::clearMessage) { Text("OK") }
                        }
                    }
                }
                if (state.groups.isEmpty() && state.loaded && !state.refreshing) {
                    item {
                        when (state.filter) {
                            DueFilter.OPEN -> EmptyState(
                                "🎉",
                                "Nothing due in the next 45 days",
                                "Add your loans, credit cards and recurring payments, and their dues show up here.",
                            )
                            DueFilter.PAID -> EmptyState("🧾", "Nothing paid yet", "Dues you mark paid in the last 30 days show here.")
                            DueFilter.SKIPPED -> EmptyState("⏭️", "Nothing skipped", "Dues you skip in the last 30 days show here.")
                        }
                    }
                }
                state.groups.forEach { group ->
                    item(key = "header-${group.label}") {
                        SectionLabel(
                            group.label,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(group.dues, key = { it.id }) { due ->
                        DueCard(
                            due = due,
                            accountName = due.accountId?.let(state.accountNames::get),
                            today = state.today,
                            busy = state.busyDueId == due.id,
                            onPay = { viewModel.openPayDialog(due) },
                            onSkip = { viewModel.skip(due) },
                            onReopen = { viewModel.reopen(due) },
                        )
                    }
                }
            }
        }
    }

    state.dialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("Pay ${dialog.due.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val quick = buildList {
                        if (dialog.due.amount > Money.ZERO) add("Full" to dialog.due.amount)
                        dialog.due.minDue?.takeIf { it > Money.ZERO && it < dialog.due.amount }?.let { add("Minimum" to it) }
                    }
                    if (quick.size > 1) {
                        ChoiceChips(
                            options = quick,
                            selected = quick.firstOrNull { it.second.toInputString() == dialog.amount },
                            onSelect = { viewModel.setDialogAmount(it.second.toInputString()) },
                            label = { "${it.first} ${it.second.formatInr()}" },
                        )
                    }
                    AmountField(
                        value = dialog.amount,
                        onValueChange = viewModel::setDialogAmount,
                        label = "Amount paid",
                        supportingText = if (dialog.due.isEstimate) "This bill is an estimate: enter what you actually paid" else null,
                    )
                    SectionLabel("Paid from")
                    ChoiceChips(
                        options = state.accounts,
                        selected = state.accounts.firstOrNull { it.id == dialog.accountId },
                        onSelect = { viewModel.setDialogAccount(it.id) },
                        label = { it.name },
                    )
                    DateField(dialog.paidOn, viewModel::setDialogDate, label = "Paid on")
                    ErrorText(dialog.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPay, enabled = !dialog.saving && dialog.accountId != null) {
                    Text(if (dialog.saving) "Saving…" else "Mark paid")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Totals(overdue: Money, next30: Money) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Column {
            SectionLabel("Due in 30 days")
            Text(next30.formatInr(), style = MaterialTheme.typography.headlineSmall)
        }
        if (overdue > Money.ZERO) {
            Column {
                SectionLabel("Overdue")
                Text(overdue.formatInr(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DueCard(
    due: Due,
    accountName: String?,
    today: LocalDate,
    busy: Boolean,
    onPay: () -> Unit,
    onSkip: () -> Unit,
    onReopen: () -> Unit,
) {
    val overdue = due.status == DueStatus.OVERDUE
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (overdue) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(due.sourceType.emoji, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f)) {
                    Text(due.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        dueSubtitle(due, accountName, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (due.isEstimate && due.amount == Money.ZERO) "—" else due.amount.formatInr(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (due.isEstimate) {
                        Text("estimate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (due.isOpen) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onPay, enabled = !busy) { Text("Mark paid") }
                    TextButton(onClick = onSkip, enabled = !busy) { Text("Skip") }
                }
            } else {
                TextButton(onClick = onReopen, enabled = !busy) {
                    Text(if (due.status == DueStatus.PAID) "Undo payment" else "Undo skip")
                }
            }
        }
    }
}

private fun dueSubtitle(due: Due, accountName: String?, today: LocalDate): String = buildList {
    when (due.status) {
        DueStatus.PAID -> add("Paid ${due.paidOn?.formatRelative(today) ?: ""}".trim() + (due.paidAmount?.takeIf { it != due.amount }?.let { " · ${it.formatInr()}" } ?: ""))
        DueStatus.SKIPPED -> add("Skipped · was due ${due.dueDate.formatRelative(today)}")
        DueStatus.OVERDUE -> add("Was due ${due.dueDate.formatRelative(today)}")
        DueStatus.UPCOMING -> add("Due ${due.dueDate.formatRelative(today)}")
    }
    if (due.isOpen && due.autoDebit) add("auto-debit")
    if (due.isOpen && due.sourceType == DueSource.CARD && due.isEstimate) add("enter the bill from Cards")
    accountName?.let { add("from $it") }
}.joinToString(" · ")
