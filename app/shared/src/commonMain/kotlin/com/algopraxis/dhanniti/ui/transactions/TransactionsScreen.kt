package com.algopraxis.dhanniti.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.domain.Transaction
import com.algopraxis.dhanniti.domain.formatPeriod
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.resources.Res
import com.algopraxis.dhanniti.resources.ic_add
import com.algopraxis.dhanniti.resources.ic_label
import com.algopraxis.dhanniti.ui.components.AmountText
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.components.ChoiceChips
import com.algopraxis.dhanniti.ui.components.EmptyState
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.LoadingOrError
import com.algopraxis.dhanniti.ui.components.SectionLabel
import com.algopraxis.dhanniti.ui.components.TabScaffold
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onAdd: (Direction) -> Unit,
    onOpen: (Long) -> Unit,
    onOpenCategories: () -> Unit,
    viewModel: TransactionsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TabScaffold(
        title = "Transactions",
        actions = {
            IconButton(onClick = onOpenCategories) { AppIcon(Res.drawable.ic_label, "Categories") }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(state.filter.direction ?: Direction.DEBIT) }) {
                AppIcon(Res.drawable.ic_add, "Add transaction", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
    ) { padding ->
        if (!state.loaded) {
            LoadingOrError(state.error, onRetry = viewModel::refresh, modifier = Modifier.padding(padding))
            return@TabScaffold
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    ChoiceChips(
                        options = TransactionFilter.entries,
                        selected = state.filter,
                        onSelect = viewModel::setFilter,
                        label = { it.label },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                state.error?.let { item { ErrorText(it, Modifier.padding(16.dp)) } }
                if (state.groups.isEmpty()) {
                    item {
                        EmptyState("🧾", "No transactions yet", "Tap + to record a spend or an income.")
                    }
                }
                state.groups.forEach { (date, transactions) ->
                    item(key = "header-$date") {
                        SectionLabel(
                            date.formatRelative(state.today),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(transactions, key = { it.id }) { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            category = transaction.categoryId?.let(state.categories::get),
                            accountName = state.accountNames[transaction.accountId],
                            onClick = { onOpen(transaction.id) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
                if (state.hasMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (state.loadingMore) CircularProgressIndicator()
                            else TextButton(onClick = viewModel::loadMore) { Text("Load older transactions") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionRow(
    transaction: Transaction,
    category: Category?,
    accountName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleDate: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(category?.icon ?: "•") }
        }
        Column(Modifier.weight(1f)) {
            Text(
                transaction.description?.takeIf { it.isNotBlank() } ?: category?.name ?: "Uncategorised",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                category?.name?.takeIf { transaction.description?.isNotBlank() == true },
                accountName,
                transaction.period?.let { "for ${formatPeriod(it)}" },
                subtitleDate,
            )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AmountText(transaction.amount, transaction.direction)
    }
}
