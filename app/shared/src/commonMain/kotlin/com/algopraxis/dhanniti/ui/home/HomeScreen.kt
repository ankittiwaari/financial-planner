package com.algopraxis.dhanniti.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.resources.Res
import com.algopraxis.dhanniti.resources.ic_more
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.components.EmptyState
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.SectionLabel
import com.algopraxis.dhanniti.ui.components.TabScaffold
import com.algopraxis.dhanniti.ui.transactions.TransactionRow
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddTransaction: (Direction) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onSeeAllTransactions: () -> Unit,
    onOpenIncome: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    TabScaffold(
        title = state.user?.name?.let { "Namaste, ${it.substringBefore(' ')}" } ?: "DhanNiti",
        actions = {
            IconButton(onClick = { menuOpen = true }) { AppIcon(Res.drawable.ic_more, "More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Log out") }, onClick = { menuOpen = false; viewModel.logout() })
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.accounts.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.error?.let { item { ErrorText(it) } }

                item { BalanceCard(state) }

                if (!state.loading && state.accounts.isEmpty()) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            EmptyState("🏦", "Add your first account", "Your bank account, cash or a wallet. Transactions and salary go into it.")
                            Button(onClick = onAddAccount) { Text("Add account") }
                        }
                    }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { onAddTransaction(Direction.DEBIT) }, modifier = Modifier.weight(1f)) {
                                Text("Add expense")
                            }
                            OutlinedButton(onClick = { onAddTransaction(Direction.CREDIT) }, modifier = Modifier.weight(1f)) {
                                Text("Add income")
                            }
                        }
                    }
                }

                state.nextIncome?.let { income ->
                    item {
                        Card(onClick = onOpenIncome, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SectionLabel("Next income")
                                Text(income.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${income.amount.formatInr()} · ${income.nextPayDate!!.formatRelative(state.today)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionLabel("Purchasing power this cycle")
                            Text(
                                "Arrives in milestone 4, once EMIs and card bills are in.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (state.recent.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Recent", modifier = Modifier.weight(1f))
                            TextButton(onClick = onSeeAllTransactions) { Text("See all") }
                        }
                    }
                    items(state.recent, key = { it.id }) { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            category = transaction.categoryId?.let(state.categories::get),
                            accountName = state.accountNames[transaction.accountId],
                            subtitleDate = transaction.occurredOn.formatRelative(state.today),
                            onClick = { onOpenTransaction(transaction.id) },
                        )
                        HorizontalDivider()
                    }
                }
                item { Spacer(Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun BalanceCard(state: HomeUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                SectionLabel("Total balance")
                Text(state.totalBalance.formatInr(), style = MaterialTheme.typography.displaySmall)
            }
            state.monthSummary?.let { summary ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("In this month")
                        Text(summary.creditTotal.formatInr(), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Out this month")
                        Text(summary.debitTotal.formatInr(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
