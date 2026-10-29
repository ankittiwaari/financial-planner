package com.algopraxis.dhanniti.ui.accounts

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.algopraxis.dhanniti.domain.AccountType
import com.algopraxis.dhanniti.domain.formatInr
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
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val AccountType.emoji: String
    get() = when (this) {
        AccountType.BANK -> "🏦"
        AccountType.CASH -> "💵"
        AccountType.WALLET -> "📱"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onAdd: () -> Unit, onOpen: (Long) -> Unit, viewModel: AccountsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TabScaffold(
        title = "Accounts",
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                AppIcon(Res.drawable.ic_add, "Add account", tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
                if (state.accounts.isEmpty() && !state.refreshing) {
                    item { EmptyState("🏦", "No accounts yet", "Add the bank account your salary lands in, plus cash or wallets you spend from.") }
                } else if (state.accounts.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            SectionLabel("Total across accounts")
                            Text(state.total.formatInr(), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                items(state.accounts, key = { it.id }) { account ->
                    AccountCard(account, onClick = { onOpen(account.id) })
                }
            }
        }
    }
}

@Composable
private fun AccountCard(account: Account, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(account.type.emoji, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    account.type.label + if (account.isPrimary) " · Primary" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                account.balance.formatInr(),
                style = MaterialTheme.typography.titleMedium,
                color = if (account.balance.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun AccountEditorScreen(
    id: Long,
    onDone: () -> Unit,
    viewModel: AccountEditorViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmArchive by remember { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    EditorScaffold(title = if (state.isNew) "Add account" else "Edit account", onBack = onDone) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.currentBalance?.let {
                Column {
                    SectionLabel("Current balance")
                    Text(it.formatInr(), style = MaterialTheme.typography.headlineSmall)
                }
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                placeholder = { Text("e.g. HDFC Savings") },
                singleLine = true,
                isError = state.showNameError,
                supportingText = if (state.showNameError) ({ Text("Give the account a name") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Type")
                ChoiceChips(
                    options = AccountType.entries,
                    selected = state.type,
                    onSelect = viewModel::setType,
                    label = { "${it.emoji} ${it.label}" },
                )
            }
            AmountField(
                value = state.openingBalance,
                onValueChange = viewModel::setOpeningBalance,
                label = "Opening balance",
                allowNegative = true,
                isError = state.showBalanceError,
                supportingText = if (state.showBalanceError) "Enter a valid amount" else "What the account held before you started tracking",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Primary account", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Picked by default for new transactions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.isPrimary, onCheckedChange = viewModel::setPrimary)
            }

            ErrorText(state.error)

            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (state.saving) "Saving…" else "Save")
            }
            if (!state.isNew) {
                OutlinedButton(onClick = { confirmArchive = true }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text("Archive account")
                }
            }
        }
    }

    if (confirmArchive) {
        ConfirmDialog(
            title = "Archive ${state.name}?",
            body = "It disappears from your lists and can't take new transactions. Its history stays.",
            confirmLabel = "Archive",
            onConfirm = { confirmArchive = false; viewModel.archive() },
            onDismiss = { confirmArchive = false },
        )
    }
}
