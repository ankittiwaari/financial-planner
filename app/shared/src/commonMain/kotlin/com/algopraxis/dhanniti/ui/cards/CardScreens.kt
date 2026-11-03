package com.algopraxis.dhanniti.ui.cards

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import com.algopraxis.dhanniti.domain.CreditCard
import com.algopraxis.dhanniti.domain.EstimateMode
import com.algopraxis.dhanniti.domain.StatementStatus
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatPeriod
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.domain.ordinal
import com.algopraxis.dhanniti.resources.Res
import com.algopraxis.dhanniti.resources.ic_add
import com.algopraxis.dhanniti.ui.components.AmountField
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.components.ChoiceChips
import com.algopraxis.dhanniti.ui.components.ConfirmDialog
import com.algopraxis.dhanniti.ui.components.EditorScaffold
import com.algopraxis.dhanniti.ui.components.EmptyState
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.NumberField
import com.algopraxis.dhanniti.ui.components.SectionLabel
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onAddAccount: () -> Unit,
    viewModel: CardsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    EditorScaffold(
        title = "Credit cards",
        onBack = onBack,
        floatingActionButton = {
            if (state.hasAccounts) {
                FloatingActionButton(onClick = onAdd) {
                    AppIcon(Res.drawable.ic_add, "Add card", tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                    !state.hasAccounts && state.loaded -> item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            EmptyState("🏦", "Add an account first", "Card bills are paid from one of your accounts.")
                            Button(onClick = onAddAccount) { Text("Add account") }
                        }
                    }
                    state.cards.isEmpty() && state.loaded -> item {
                        EmptyState(
                            "💳",
                            "No credit cards",
                            "Add a card with its statement and due days. Only a nickname and the last 4 digits are stored.",
                        )
                    }
                }
                items(state.cards, key = { it.id }) { card ->
                    CreditCardCard(
                        card = card,
                        accountName = state.accountNames[card.payFromAccountId],
                        today = state.today,
                        onClick = { onOpen(card.id) },
                        onEnterBill = { viewModel.openStatementDialog(card) },
                    )
                }
            }
        }
    }

    state.dialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("${dialog.card.nickname} bill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("Statement for")
                    ChoiceChips(dialog.periods, dialog.period, viewModel::setDialogPeriod, label = ::formatPeriod)
                    AmountField(value = dialog.totalDue, onValueChange = viewModel::setDialogTotal, label = "Total due")
                    AmountField(value = dialog.minDue, onValueChange = viewModel::setDialogMin, label = "Minimum due (optional)")
                    ErrorText(dialog.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmStatement, enabled = !dialog.saving) {
                    Text(if (dialog.saving) "Saving…" else "Save bill")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CreditCardCard(
    card: CreditCard,
    accountName: String?,
    today: LocalDate,
    onClick: () -> Unit,
    onEnterBill: () -> Unit,
) {
    val statement = card.latestStatement
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("💳", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f)) {
                    Text(card.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            "Statement ${ordinal(card.statementDay)}, due ${ordinal(card.dueDay)}",
                            accountName?.let { "paid from $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                statement?.let { Text(it.totalDue.formatInr(), style = MaterialTheme.typography.titleMedium) }
            }
            if (statement != null) {
                val status = when (statement.status) {
                    StatementStatus.ESTIMATED -> "Estimated · enter the real bill"
                    StatementStatus.PARTIALLY_PAID -> "Part paid · ${statement.amountPaid.formatInr()} so far"
                    else -> statement.status.label
                }
                Text(
                    "${formatPeriod(statement.period)} bill, due ${statement.dueDate.formatRelative(today)} · $status",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilledTonalButton(onClick = onEnterBill) { Text("Enter bill") }
        }
    }
}

@Composable
fun CardEditorScreen(
    id: Long,
    onDone: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: CardEditorViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    EditorScaffold(title = if (state.isNew) "Add card" else "Edit card", onBack = onDone) { padding ->
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
                value = state.nickname,
                onValueChange = viewModel::setNickname,
                label = { Text("Nickname") },
                placeholder = { Text("e.g. ICICI Amazon Pay") },
                singleLine = true,
                isError = "nickname" in state.fieldErrors,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    value = state.last4,
                    onValueChange = viewModel::setLast4,
                    label = "Last 4 digits",
                    maxDigits = 4,
                    isError = "last4" in state.fieldErrors,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.issuer,
                    onValueChange = viewModel::setIssuer,
                    label = { Text("Bank (optional)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    value = state.statementDay,
                    onValueChange = viewModel::setStatementDay,
                    label = "Statement day",
                    isError = "statementDay" in state.fieldErrors,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = state.dueDay,
                    onValueChange = viewModel::setDueDay,
                    label = "Due day",
                    isError = "dueDay" in state.fieldErrors,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Day of the month, 1–31. A due day on or before the statement day means the following month.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AmountField(
                value = state.creditLimit,
                onValueChange = viewModel::setCreditLimit,
                label = "Credit limit (optional)",
                isError = "creditLimit" in state.fieldErrors,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Bill paid from")
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
                SectionLabel("Until the real bill is entered, expect")
                ChoiceChips(EstimateMode.entries, state.estimateMode, viewModel::setEstimateMode, label = { it.label })
            }
            if (state.estimateMode == EstimateMode.FIXED) {
                AmountField(
                    value = state.fixedEstimate,
                    onValueChange = viewModel::setFixedEstimate,
                    label = "Expected bill",
                    isError = "fixedEstimate" in state.fieldErrors,
                )
            }

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
                    Text("Delete card")
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${state.nickname}?",
            body = "Its statements and unpaid bills are removed. Bills you already paid stay in your transactions.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
