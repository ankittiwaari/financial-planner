package com.algopraxis.dhanniti.ui.loans

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.domain.Loan
import com.algopraxis.dhanniti.domain.LoanScheduleRow
import com.algopraxis.dhanniti.domain.LoanType
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.domain.formatShort
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
import com.algopraxis.dhanniti.ui.components.NumberField
import com.algopraxis.dhanniti.ui.components.SectionLabel
import com.algopraxis.dhanniti.ui.components.SwitchRow
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val LoanType.emoji: String
    get() = when (this) {
        LoanType.HOME -> "🏠"
        LoanType.CAR -> "🚗"
        LoanType.PERSONAL -> "👤"
        LoanType.EDUCATION -> "🎓"
        LoanType.OTHER -> "🏦"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoansScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onAddAccount: () -> Unit,
    viewModel: LoansViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    EditorScaffold(
        title = "Loans",
        onBack = onBack,
        floatingActionButton = {
            if (state.hasAccounts) {
                FloatingActionButton(onClick = onAdd) {
                    AppIcon(Res.drawable.ic_add, "Add loan", tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                            EmptyState("🏦", "Add an account first", "EMIs are paid from one of your accounts.")
                            Button(onClick = onAddAccount) { Text("Add account") }
                        }
                    }
                    state.loans.isEmpty() && state.loaded -> item {
                        EmptyState("🏠", "No loans", "Add a home, car or personal loan to track its EMIs and what's left to repay.")
                    }
                    state.loans.any { it.isActive } -> item {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            SectionLabel("Outstanding across loans")
                            Text(state.totalOutstanding.formatInr(), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                items(state.loans, key = { it.id }) { loan ->
                    LoanCard(loan, state.today, onClick = { onOpen(loan.id) })
                }
            }
        }
    }
}

@Composable
private fun LoanCard(loan: Loan, today: LocalDate, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(loan.type.emoji, style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f)) {
                    Text(loan.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (loan.isActive) "${loan.emi.formatInr()} a month" else "Closed ${loan.closedOn?.formatShort().orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loan.isActive) Text(loan.outstanding.formatInr(), style = MaterialTheme.typography.titleMedium)
            }
            if (loan.isActive) {
                RepaymentProgress(loan)
                loan.nextEmiDate?.let { Text("Next EMI: ${it.formatRelative(today)}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun RepaymentProgress(loan: Loan) {
    val total = loan.emisPaid + loan.emisLeft
    LinearProgressIndicator(
        progress = { if (total == 0) 1f else loan.emisPaid.toFloat() / total },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "${loan.emisPaid} of $total EMIs paid · ${loan.emisLeft} left",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun LoanDetailScreen(
    id: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: LoanDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.done) { if (state.done) onBack() }
    LaunchedEffect(state.loan) { if (state.loan != null) viewModel.loadSchedule() }

    EditorScaffold(
        title = state.loan?.name ?: "Loan",
        onBack = onBack,
        actions = { if (state.loan?.isActive == true) TextButton(onClick = onEdit) { Text("Edit") } },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            state.error?.let { item { ErrorText(it) } }
            state.loan?.let { loan ->
                item { LoanSummary(loan, state.accountName) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loan.isActive) {
                            OutlinedButton(onClick = { confirm = "close" }, enabled = !state.busy) { Text("Mark repaid") }
                        }
                        OutlinedButton(onClick = { confirm = "delete" }, enabled = !state.busy) { Text("Delete") }
                    }
                }
            }
            item { SectionLabel("Repayment schedule", Modifier.padding(top = 8.dp)) }
            if (state.loadingSchedule && state.schedule.isEmpty()) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item { ScheduleHeader() }
            items(state.schedule, key = { it.installment }) { row -> ScheduleRow(row) }
        }
    }

    when (confirm) {
        "close" -> ConfirmDialog(
            title = "Mark ${state.loan?.name} repaid?",
            body = "For a loan you foreclosed or prepaid in full. Its unpaid EMIs are removed from your dues.",
            confirmLabel = "Mark repaid",
            onConfirm = { confirm = null; viewModel.close() },
            onDismiss = { confirm = null },
        )
        "delete" -> ConfirmDialog(
            title = "Delete ${state.loan?.name}?",
            body = "Unpaid EMIs are removed. EMIs you already marked paid stay in your transactions.",
            confirmLabel = "Delete",
            onConfirm = { confirm = null; viewModel.delete() },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun LoanSummary(loan: Loan, accountName: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(if (loan.isActive) "Outstanding" else "Closed")
            Text(
                if (loan.isActive) loan.outstanding.formatInr() else loan.closedOn?.formatShort().orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (loan.isActive) RepaymentProgress(loan)
            HorizontalDivider()
            Fact("EMI", loan.emi.formatInr() + if (loan.emiAmount == null) " (calculated)" else "")
            Fact("Borrowed", loan.principal.formatInr())
            Fact("Interest", "${loan.interestRate}% a year · ${loan.totalInterest.formatInr()} over the loan")
            Fact("Term", "${loan.tenureMonths} months · ${loan.firstEmiDate.formatShort()} to ${loan.lastEmiDate?.formatShort().orEmpty()}")
            Fact("Paid from", listOfNotNull(accountName, if (loan.autoDebit) "auto-debit" else null).joinToString(" · ").ifEmpty { "—" })
            loan.lender?.let { Fact("Lender", it) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun ScheduleHeader() {
    Row(Modifier.fillMaxWidth()) {
        listOf("#" to 0.1f, "Date" to 0.28f, "Interest" to 0.3f, "Left after" to 0.32f).forEach { (label, weight) ->
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(weight))
        }
    }
}

@Composable
private fun ScheduleRow(row: LoanScheduleRow) {
    val color = if (row.paid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodySmall
    Row(Modifier.fillMaxWidth()) {
        Text(row.installment.toString(), style = style, color = color, modifier = Modifier.weight(0.1f))
        Text(row.dueDate.formatShort(), style = style, color = color, modifier = Modifier.weight(0.28f))
        Text(row.interest.formatInr(), style = style, color = color, modifier = Modifier.weight(0.3f))
        Text((if (row.paid) "✓ " else "") + row.outstanding.formatInr(), style = style, color = color, modifier = Modifier.weight(0.32f))
    }
}

@Composable
fun LoanEditorScreen(
    id: Long,
    onDone: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: LoanEditorViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) { if (state.savedId != null) onDone() }

    EditorScaffold(title = if (state.isNew) "Add loan" else "Edit loan", onBack = onDone) { padding ->
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
                placeholder = { Text("e.g. HDFC Home Loan") },
                singleLine = true,
                isError = "name" in state.fieldErrors,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Type")
                ChoiceChips(LoanType.entries, state.type, viewModel::setType, label = { "${it.emoji} ${it.label}" })
            }
            OutlinedTextField(
                value = state.lender,
                onValueChange = viewModel::setLender,
                label = { Text("Lender (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            AmountField(
                value = state.principal,
                onValueChange = viewModel::setPrincipal,
                label = "Amount borrowed",
                isError = "principal" in state.fieldErrors,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.interestRate,
                    onValueChange = viewModel::setInterestRate,
                    label = { Text("Interest % a year") },
                    singleLine = true,
                    isError = "interestRate" in state.fieldErrors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = state.tenureMonths,
                    onValueChange = viewModel::setTenure,
                    label = "Months",
                    maxDigits = 3,
                    isError = "tenureMonths" in state.fieldErrors,
                    modifier = Modifier.weight(1f),
                )
            }
            DateField(state.firstEmiDate, viewModel::setFirstEmiDate, label = "First EMI date")
            AmountField(
                value = state.emiAmount,
                onValueChange = viewModel::setEmiAmount,
                label = "EMI (optional)",
                isError = "emiAmount" in state.fieldErrors,
                supportingText = "From your loan statement. Leave blank to calculate it.",
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("EMIs paid from")
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
            SwitchRow("Auto-debit (NACH/ECS)", "The bank takes the EMI on its own", state.autoDebit, viewModel::setAutoDebit)

            ErrorText(state.error)

            Button(
                onClick = viewModel::save,
                enabled = !state.saving && state.accountId != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        }
    }
}
