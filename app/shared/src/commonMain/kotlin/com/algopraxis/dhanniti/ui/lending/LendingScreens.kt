package com.algopraxis.dhanniti.ui.lending

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import com.algopraxis.dhanniti.domain.Account
import com.algopraxis.dhanniti.domain.Lending
import com.algopraxis.dhanniti.domain.LendingStatus
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.PersonBalance
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.domain.formatShort
import com.algopraxis.dhanniti.domain.personKey
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Account choices for money moving in or out, with "not tracked" (null) last. */
@Composable
private fun AccountChoice(accounts: List<Account>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    ChoiceChips(
        options = accounts.map { it.id as Long? } + null,
        selected = selectedId,
        onSelect = onSelect,
        label = { id -> accounts.firstOrNull { it.id == id }?.name ?: "Not from an account" },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LentMoneyScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenPerson: (String) -> Unit,
    viewModel: LentMoneyViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    EditorScaffold(
        title = "Lent money",
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                AppIcon(Res.drawable.ic_add, "Record money lent", tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                if (state.receivables.people.isEmpty() && state.loaded) {
                    item {
                        EmptyState(
                            "🤝",
                            "No money lent",
                            "Record what you lend and what comes back. It shows as owed to you, never as money you can spend.",
                        )
                    }
                } else if (state.receivables.people.isNotEmpty()) {
                    item { ReceivablesHeader(state.receivables.totalOutstanding, state.receivables.peopleOwing, state.receivables.overdueTotal) }
                }
                if (state.owing.isNotEmpty()) {
                    item { SectionLabel("Owes you", Modifier.padding(top = 8.dp)) }
                    items(state.owing, key = { "owing-${personKey(it.name)}" }) { person ->
                        PersonCard(person, state.today, onClick = { onOpenPerson(person.name) })
                    }
                }
                if (state.squared.isNotEmpty()) {
                    item { SectionLabel("All square", Modifier.padding(top = 8.dp)) }
                    items(state.squared, key = { "square-${personKey(it.name)}" }) { person ->
                        PersonCard(person, state.today, onClick = { onOpenPerson(person.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceivablesHeader(total: Money, people: Int, overdue: Money) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Column {
            SectionLabel("Owed to you")
            Text(total.formatInr(), style = MaterialTheme.typography.headlineMedium)
            Text(
                if (people == 1) "by 1 person" else "by $people people",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (overdue > Money.ZERO) {
            Column {
                SectionLabel("Overdue")
                Text(overdue.formatInr(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PersonCard(person: PersonBalance, today: LocalDate, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(person.name, style = MaterialTheme.typography.titleMedium)
                Text(personSubtitle(person, today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (person.outstanding > Money.ZERO) person.outstanding.formatInr() else "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (person.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (person.overdue) Text("overdue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun personSubtitle(person: PersonBalance, today: LocalDate): String = buildList {
    add("Lent ${person.lent.formatInr()}")
    if (person.repaid > Money.ZERO) add("got back ${person.repaid.formatInr()}")
    if (person.writtenOff > Money.ZERO) add("${person.writtenOff.formatInr()} written off")
    person.nextExpectedBy?.let { if (person.outstanding > Money.ZERO) add("expected ${it.formatRelative(today)}") }
}.joinToString(" · ")

@Composable
fun PersonScreen(
    name: String,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: PersonViewModel = koinViewModel { parametersOf(name) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmWriteOff by remember { mutableStateOf<Lending?>(null) }

    EditorScaffold(
        title = state.name,
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(state.name) }) {
                AppIcon(Res.drawable.ic_add, "Lend ${state.name} more", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            state.error?.let { item { ErrorText(it) } }
            state.balance?.let { balance ->
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        SectionLabel(if (balance.outstanding > Money.ZERO) "Owes you" else "All square")
                        Text(balance.outstanding.formatInr(), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            personSubtitle(balance, state.today).substringBefore(" · expected"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        balance.contact?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (state.lendings.isEmpty() && state.balance == null) {
                item { EmptyState("🤝", "Nothing recorded", "Lendings for ${state.name} show up here.") }
            }
            items(state.lendings, key = { it.id }) { lending ->
                LendingCard(
                    lending = lending,
                    accountNames = state.accountNames,
                    today = state.today,
                    busy = state.busyId == lending.id,
                    onRepay = { viewModel.openRepayDialog(lending) },
                    onEdit = { onEdit(lending.id) },
                    onWriteOff = { confirmWriteOff = lending },
                    onReopen = { viewModel.reopen(lending) },
                    onUndoRepayment = { viewModel.deleteRepayment(lending, it) },
                )
            }
        }
    }

    confirmWriteOff?.let { lending ->
        ConfirmDialog(
            title = "Write off ${lending.outstanding.formatInr()}?",
            body = "It stops counting as owed to you. If ${state.name} pays later, record the repayment and it comes back.",
            confirmLabel = "Write off",
            onConfirm = { confirmWriteOff = null; viewModel.writeOff(lending) },
            onDismiss = { confirmWriteOff = null },
        )
    }

    state.dialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("${state.name} paid back") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${dialog.lending.outstanding.formatInr()} still owed from ${dialog.lending.lentOn.formatShort()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AmountField(value = dialog.amount, onValueChange = viewModel::setDialogAmount, label = "Amount received")
                    DateField(dialog.receivedOn, viewModel::setDialogDate, label = "Received on")
                    SectionLabel("Into")
                    AccountChoice(state.accounts, dialog.accountId, viewModel::setDialogAccount)
                    ErrorText(dialog.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRepay, enabled = !dialog.saving) {
                    Text(if (dialog.saving) "Saving…" else "Record")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LendingCard(
    lending: Lending,
    accountNames: Map<Long, String>,
    today: LocalDate,
    busy: Boolean,
    onRepay: () -> Unit,
    onEdit: () -> Unit,
    onWriteOff: () -> Unit,
    onReopen: () -> Unit,
    onUndoRepayment: (Long) -> Unit,
) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = if (lending.isOverdue) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lent ${lending.amount.formatInr()}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            lending.lentOn.formatShort(),
                            lending.accountId?.let(accountNames::get)?.let { "from $it" },
                            lending.notes,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (lending.isOutstanding) lending.outstanding.formatInr() else lending.status.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (lending.isOutstanding) {
                        Text("still owed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            lending.expectedBy?.let {
                if (lending.isOutstanding) {
                    Text(
                        (if (lending.isOverdue) "Was expected " else "Expected back ") + it.formatRelative(today),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (lending.repayments.isNotEmpty()) {
                HorizontalDivider()
                lending.repayments.forEach { repayment ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "+${repayment.amount.formatInr()} · ${repayment.receivedOn.formatShort()}" +
                                (repayment.accountId?.let(accountNames::get)?.let { " · into $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onUndoRepayment(repayment.id) }, enabled = !busy) { Text("Undo") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (lending.status) {
                    LendingStatus.OPEN, LendingStatus.PARTIALLY_REPAID -> {
                        FilledTonalButton(onClick = onRepay, enabled = !busy) { Text("Got money back") }
                        TextButton(onClick = onWriteOff, enabled = !busy) { Text("Write off") }
                    }
                    LendingStatus.WRITTEN_OFF -> TextButton(onClick = onReopen, enabled = !busy) { Text("Undo write-off") }
                    LendingStatus.SETTLED -> Unit
                }
            }
        }
    }
}

@Composable
fun LendingEditorScreen(
    id: Long,
    personName: String,
    onDone: () -> Unit,
    viewModel: LendingEditorViewModel = koinViewModel { parametersOf(id, personName) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    EditorScaffold(title = if (state.isNew) "Lend money" else "Edit lending", onBack = onDone) { padding ->
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
                value = state.personName,
                onValueChange = viewModel::setPersonName,
                label = { Text("Lent to") },
                placeholder = { Text("Their name") },
                singleLine = true,
                isError = "personName" in state.fieldErrors,
                modifier = Modifier.fillMaxWidth(),
            )
            val suggestions = state.knownPeople.filter {
                state.personName.isNotBlank() && personKey(it) != personKey(state.personName) &&
                    it.contains(state.personName.trim(), ignoreCase = true)
            }.take(4)
            if (state.isNew && suggestions.isNotEmpty()) {
                ChoiceChips(suggestions, null, viewModel::setPersonName, label = { it })
            }
            AmountField(
                value = state.amount,
                onValueChange = viewModel::setAmount,
                label = "Amount",
                isError = "amount" in state.fieldErrors,
                supportingText = if (state.repaid > Money.ZERO) "At least ${state.repaid.formatInr()}, already repaid" else null,
            )
            DateField(state.lentOn, viewModel::setLentOn, label = "Lent on")
            SwitchRow(
                "Expect it back by a date",
                "Shows as overdue after that",
                state.expectedBy != null,
                { on -> viewModel.setExpectedBy(if (on) state.lentOn.plus(DatePeriod(months = 1)) else null) },
            )
            state.expectedBy?.let { expected ->
                DateField(expected, viewModel::setExpectedBy, label = "Expected back by")
                if ("expectedBy" in state.fieldErrors) ErrorText("Pick a date on or after the day you lent it")
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Paid from")
                AccountChoice(state.accounts, state.accountId, viewModel::setAccount)
                Text(
                    if (state.accountId == null) "Nothing is taken out of your accounts. Use this for money lent before you started tracking."
                    else "Recorded as money leaving this account. It isn't counted as spending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.contact,
                onValueChange = viewModel::setContact,
                label = { Text("Phone or contact (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = { Text("Note (optional)") },
                placeholder = { Text("e.g. for the bike repair") },
                modifier = Modifier.fillMaxWidth(),
            )

            ErrorText(state.error)

            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (state.saving) "Saving…" else "Save")
            }
            if (!state.isNew) {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete")
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this lending?",
            body = "Its repayments go too, along with the transactions they recorded, as if it never happened.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
