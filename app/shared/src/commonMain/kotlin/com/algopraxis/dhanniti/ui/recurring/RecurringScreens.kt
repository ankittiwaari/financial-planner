package com.algopraxis.dhanniti.ui.recurring

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.Frequency
import com.algopraxis.dhanniti.domain.RecurringPayment
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.resources.Res
import com.algopraxis.dhanniti.resources.ic_add
import com.algopraxis.dhanniti.ui.components.AmountField
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.components.ChoiceChips
import com.algopraxis.dhanniti.ui.components.ConfirmDialog
import com.algopraxis.dhanniti.ui.components.DateField
import com.algopraxis.dhanniti.ui.components.EditorScaffold
import com.algopraxis.dhanniti.ui.components.EmptyState
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.SectionLabel
import com.algopraxis.dhanniti.ui.components.SwitchRow
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onAddAccount: () -> Unit,
    viewModel: RecurringViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    EditorScaffold(
        title = "Recurring payments",
        onBack = onBack,
        floatingActionButton = {
            if (state.hasAccounts) {
                FloatingActionButton(onClick = onAdd) {
                    AppIcon(Res.drawable.ic_add, "Add recurring payment", tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                when {
                    !state.hasAccounts && state.loaded -> item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            EmptyState("🏦", "Add an account first", "Recurring payments come out of one of your accounts.")
                            Button(onClick = onAddAccount) { Text("Add account") }
                        }
                    }
                    state.payments.isEmpty() && state.loaded -> item {
                        EmptyState("🔁", "No recurring payments", "Add rent, SIPs, insurance premiums and subscriptions.")
                    }
                    state.payments.isNotEmpty() -> item {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            SectionLabel("About a month")
                            Text(state.monthlyTotal.formatInr(), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                items(state.payments, key = { it.id }) { payment ->
                    RecurringCard(
                        payment = payment,
                        accountName = state.accountNames[payment.accountId],
                        category = payment.categoryId?.let(state.categories::get),
                        today = state.today,
                        onClick = { onOpen(payment.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecurringCard(payment: RecurringPayment, accountName: String?, category: Category?, today: LocalDate, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(category?.icon ?: "🔁", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(payment.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(payment.scheduleLabel, accountName?.let { "from $it" }, if (payment.autoDebit) "auto-debit" else null)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    payment.nextDate?.let { "Next: ${it.formatRelative(today)}" } ?: "Ended",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(payment.amount.formatInr(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun RecurringEditorScreen(
    id: Long,
    onDone: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: RecurringEditorViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    EditorScaffold(title = if (state.isNew) "Add recurring payment" else "Edit recurring payment", onBack = onDone) { padding ->
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
                placeholder = { Text("e.g. Rent, Netflix, LIC premium") },
                singleLine = true,
                isError = "name" in state.fieldErrors,
                modifier = Modifier.fillMaxWidth(),
            )
            AmountField(value = state.amount, onValueChange = viewModel::setAmount, label = "Amount", isError = "amount" in state.fieldErrors)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("How often")
                ChoiceChips(Frequency.entries, state.frequency, viewModel::setFrequency, label = { it.label })
            }
            DateField(
                state.nextDate,
                viewModel::setNextDate,
                label = if (state.isNew) "First payment" else "Next payment",
            )
            Text(
                "Repeats on the same day ${if (state.frequency == Frequency.MONTHLY) "every month" else "every ${state.frequency.months} months"}. Short months use their last day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Paid from")
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
                SectionLabel("Category")
                ChoiceChips(
                    options = listOf<Category?>(null) + state.categories,
                    selected = state.categories.firstOrNull { it.id == state.categoryId },
                    onSelect = { viewModel.setCategory(it?.id) },
                    label = { it?.let { c -> "${c.icon} ${c.name}" } ?: "Default" },
                )
            }
            SwitchRow("Auto-debit", "Taken from the account automatically (standing instruction, SIP mandate)", state.autoDebit, viewModel::setAutoDebit)

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
                    Text("Delete recurring payment")
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${state.name}?",
            body = "Its unpaid dues are removed. Payments you already marked paid stay in your transactions.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
