package com.algopraxis.dhanniti.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.formatPeriod
import com.algopraxis.dhanniti.resources.Res
import com.algopraxis.dhanniti.resources.ic_delete
import com.algopraxis.dhanniti.ui.components.AmountField
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.components.ChoiceChips
import com.algopraxis.dhanniti.ui.components.ConfirmDialog
import com.algopraxis.dhanniti.ui.components.DateField
import com.algopraxis.dhanniti.ui.components.EditorScaffold
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.SectionLabel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TransactionEditorScreen(
    id: Long,
    direction: Direction,
    onDone: () -> Unit,
    viewModel: TransactionEditorViewModel = koinViewModel { parametersOf(id, direction) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    val form = state.form
    EditorScaffold(
        title = when {
            !state.isNew -> "Edit transaction"
            form.direction == Direction.CREDIT -> "Add income"
            else -> "Add expense"
        },
        onBack = onDone,
        actions = {
            if (!state.isNew) {
                IconButton(onClick = { confirmDelete = true }) { AppIcon(Res.drawable.ic_delete, "Delete") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChoiceChips(
                options = listOf(Direction.DEBIT, Direction.CREDIT),
                selected = form.direction,
                onSelect = viewModel::setDirection,
                label = { if (it == Direction.DEBIT) "Money out" else "Money in" },
            )

            AmountField(
                value = form.amount,
                onValueChange = viewModel::setAmount,
                label = "Amount",
                isError = state.showAmountError,
                supportingText = if (state.showAmountError) "Enter an amount above ₹0" else null,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Category")
                ChoiceChips(
                    options = state.categories,
                    selected = state.categories.firstOrNull { it.id == form.categoryId },
                    onSelect = { viewModel.setCategory(it.id) },
                    label = { "${it.icon} ${it.name}" },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(if (form.direction == Direction.CREDIT) "Into account" else "From account")
                if (state.accounts.isEmpty()) {
                    Text("Add an account first, from the Accounts tab.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    ChoiceChips(
                        options = state.accounts,
                        selected = state.accounts.firstOrNull { it.id == form.accountId },
                        onSelect = { viewModel.setAccount(it.id) },
                        label = { it.name },
                    )
                }
            }

            DateField(date = form.date, onDateChange = viewModel::setDate, label = "Date")

            OutlinedTextField(
                value = form.note,
                onValueChange = viewModel::setNote,
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            form.period?.let { period ->
                Text(
                    "Income credit for ${formatPeriod(period)}" + if (form.isAuto) " (posted automatically)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ErrorText(state.error)

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete transaction?",
            body = "This removes it from your ledger and updates the account balance.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
