package com.algopraxis.dhanniti.ui.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.domain.IncomeSource
import com.algopraxis.dhanniti.domain.PayRule
import com.algopraxis.dhanniti.domain.WeekendShift
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatPeriod
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.resources.Res
import com.algopraxis.dhanniti.resources.ic_add
import com.algopraxis.dhanniti.ui.components.AmountField
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.components.ChoiceChips
import com.algopraxis.dhanniti.ui.components.ConfirmDialog
import com.algopraxis.dhanniti.ui.components.EditorScaffold
import com.algopraxis.dhanniti.ui.components.EmptyState
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.SectionLabel
import com.algopraxis.dhanniti.ui.components.TabScaffold
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomeScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onAddAccount: () -> Unit,
    viewModel: IncomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TabScaffold(
        title = "Income",
        floatingActionButton = {
            if (state.hasAccounts) {
                FloatingActionButton(onClick = onAdd) {
                    AppIcon(Res.drawable.ic_add, "Add income source", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.error?.let { item { ErrorText(it) } }
                state.message?.let { message ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✓ $message", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::clearMessage) { Text("OK") }
                        }
                    }
                }
                when {
                    !state.hasAccounts && !state.refreshing -> item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            EmptyState("🏦", "Add an account first", "Your salary needs an account to land in.")
                            Button(onClick = onAddAccount) { Text("Add account") }
                        }
                    }
                    state.sources.isEmpty() && !state.refreshing -> item {
                        EmptyState("💼", "No income sources yet", "Add your salary with its payday. You can log each month's credit with one tap.")
                    }
                }
                items(state.sources, key = { it.id }) { source ->
                    IncomeCard(
                        source = source,
                        accountName = state.accountNames[source.accountId],
                        today = state.today,
                        onClick = { onOpen(source.id) },
                        onLogCredit = { viewModel.openCreditDialog(source) },
                    )
                }
            }
        }
    }

    state.dialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("Log ${dialog.source.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("For")
                    ChoiceChips(
                        options = dialog.periods,
                        selected = dialog.period,
                        onSelect = viewModel::setDialogPeriod,
                        label = ::formatPeriod,
                    )
                    AmountField(
                        value = dialog.amount,
                        onValueChange = viewModel::setDialogAmount,
                        label = "Amount received",
                        supportingText = if (dialog.source.isVariable) "Varies monthly: check the credited amount" else null,
                    )
                    Text(
                        "Goes into ${state.accountNames[dialog.source.accountId] ?: "its account"}, dated that month's payday.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ErrorText(dialog.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCredit, enabled = !dialog.saving) {
                    Text(if (dialog.saving) "Logging…" else "Log credit")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
        )
    }
}

@Composable
private fun IncomeCard(
    source: IncomeSource,
    accountName: String?,
    today: LocalDate,
    onClick: () -> Unit,
    onLogCredit: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    source.amount.formatInr() + if (source.isVariable) "*" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                listOfNotNull(source.scheduleLabel, accountName?.let { "into $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            source.nextPayDate?.let { Text("Next payday: ${it.formatRelative(today)}", style = MaterialTheme.typography.bodyMedium) }
            Text(
                source.lastCreditedPeriod?.let { "Last logged: ${formatPeriod(it)}" } ?: "Not logged yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onLogCredit) { Text("Log credit") }
        }
    }
}

@Composable
fun IncomeEditorScreen(
    id: Long,
    onDone: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: IncomeEditorViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    EditorScaffold(title = if (state.isNew) "Add income source" else "Edit income source", onBack = onDone) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                placeholder = { Text("e.g. Salary - Acme") },
                singleLine = true,
                isError = "name" in state.fieldErrors,
                modifier = Modifier.fillMaxWidth(),
            )
            AmountField(
                value = state.amount,
                onValueChange = viewModel::setAmount,
                label = if (state.isVariable) "Usual amount" else "Amount",
                isError = "amount" in state.fieldErrors,
                supportingText = "In-hand amount, after tax and deductions",
            )
            SwitchRow("Amount varies month to month", "You'll confirm the amount each time you log it", state.isVariable, viewModel::setVariable)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Credited to")
                if (state.accounts.isEmpty()) {
                    TextButton(onClick = onAddAccount) { Text("Add an account first") }
                } else {
                    ChoiceChips(
                        options = state.accounts,
                        selected = state.accounts.firstOrNull { it.id == state.accountId },
                        onSelect = { viewModel.setAccount(it.id) },
                        label = { it.name },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Payday")
                ChoiceChips(PayRule.entries, state.payRule, viewModel::setPayRule, label = { it.label })
            }
            if (state.payRule == PayRule.FIXED_DAY) {
                OutlinedTextField(
                    value = state.payDay,
                    onValueChange = viewModel::setPayDay,
                    label = { Text("Day of month") },
                    singleLine = true,
                    isError = "payDay" in state.fieldErrors,
                    supportingText = { Text("1–31. Short months use their last day.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.payRule != PayRule.LAST_WORKING_DAY) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("If payday falls on a weekend")
                    ChoiceChips(WeekendShift.entries, state.weekendShift, viewModel::setWeekendShift, label = { it.label })
                }
            }
            SwitchRow(
                "Credit automatically on payday",
                "Coming in a later update. Until then, log each credit from the Income tab.",
                state.autoCredit,
                viewModel::setAutoCredit,
            )

            ErrorText(state.error)

            Button(
                onClick = viewModel::save,
                enabled = !state.saving && state.accountId != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.saving) "Saving…" else "Save")
            }
            if (!state.isNew) {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete income source")
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${state.name}?",
            body = "Credits you already logged stay in your transactions.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
