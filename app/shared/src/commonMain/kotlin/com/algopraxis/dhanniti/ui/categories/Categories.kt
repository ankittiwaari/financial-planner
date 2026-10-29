package com.algopraxis.dhanniti.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.api.CategoryInput
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.domain.Category
import com.algopraxis.dhanniti.domain.CategoryKind
import com.algopraxis.dhanniti.resources.Res
import com.algopraxis.dhanniti.resources.ic_add
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.components.ChoiceChips
import com.algopraxis.dhanniti.ui.components.EditorScaffold
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.SectionLabel
import com.algopraxis.dhanniti.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.koin.compose.viewmodel.koinViewModel

private val SUGGESTED_ICONS = listOf(
    "🍔", "🛒", "🏠", "🚗", "⛽", "🚌", "🛍️", "💡", "📱", "💊", "🎬", "✈️",
    "🎓", "🎁", "👕", "🐶", "🏋️", "☕", "💼", "💰", "📈", "🏦", "🧾", "❤️",
)

data class CategoryDialogState(
    val id: Long? = null,
    val name: String = "",
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val icon: String = "🧾",
    val saving: Boolean = false,
    val error: String? = null,
)

data class CategoriesUiState(
    val expense: List<Category> = emptyList(),
    val income: List<Category> = emptyList(),
    val error: String? = null,
    val dialog: CategoryDialogState? = null,
)

class CategoriesViewModel(private val ledger: LedgerRepository) : ViewModel() {
    private val dialog = MutableStateFlow<CategoryDialogState?>(null)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<CategoriesUiState> = combine(ledger.categories, dialog, error) { categories, dialog, error ->
        CategoriesUiState(
            expense = categories.filter { it.kind == CategoryKind.EXPENSE },
            income = categories.filter { it.kind == CategoryKind.INCOME },
            error = error,
            dialog = dialog,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    init {
        launchCatching(onError = { error.value = it }) { ledger.refreshCategories() }
    }

    fun add() {
        dialog.value = CategoryDialogState()
    }

    fun edit(category: Category) {
        dialog.value = CategoryDialogState(category.id, category.name, category.kind, category.icon)
    }

    fun dismiss() {
        dialog.value = null
    }

    fun setName(value: String) = dialog.update { it?.copy(name = value.take(40), error = null) }
    fun setKind(kind: CategoryKind) = dialog.update { it?.copy(kind = kind) }
    fun setIcon(icon: String) = dialog.update { it?.copy(icon = icon) }

    fun save() {
        val current = dialog.value ?: return
        val name = current.name.trim()
        if (name.isEmpty()) {
            dialog.update { it?.copy(error = "Give the category a name") }
            return
        }
        dialog.update { it?.copy(saving = true, error = null) }
        launchCatching(onError = { message -> dialog.update { it?.copy(saving = false, error = message) } }) {
            ledger.saveCategory(current.id, CategoryInput(name, current.kind, current.icon))
            dialog.value = null
        }
    }

    fun archive() {
        val id = dialog.value?.id ?: return
        dialog.update { it?.copy(saving = true, error = null) }
        launchCatching(onError = { message -> dialog.update { it?.copy(saving = false, error = message) } }) {
            ledger.archiveCategory(id)
            dialog.value = null
        }
    }
}

@Composable
fun CategoriesScreen(onBack: () -> Unit, viewModel: CategoriesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    EditorScaffold(title = "Categories", onBack = onBack) { padding ->
        Scaffold(
            modifier = Modifier.padding(padding),
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            floatingActionButton = {
                FloatingActionButton(onClick = viewModel::add) {
                    AppIcon(Res.drawable.ic_add, "Add category", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            },
        ) { inner ->
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize().padding(inner)) {
                state.error?.let { item { ErrorText(it, Modifier.padding(16.dp)) } }
                section("Expense", state.expense, viewModel::edit)
                section("Income", state.income, viewModel::edit)
            }
        }
    }

    state.dialog?.let { dialog -> CategoryDialog(dialog, viewModel) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    categories: List<Category>,
    onClick: (Category) -> Unit,
) {
    item { SectionLabel(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
    items(categories, key = { it.id }) { category ->
        Row(
            Modifier.fillMaxWidth().clickable { onClick(category) }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(category.icon, style = MaterialTheme.typography.titleLarge)
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(dialog: CategoryDialogState, viewModel: CategoriesViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(if (dialog.id == null) "New category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceChips(CategoryKind.entries, dialog.kind, viewModel::setKind, label = { it.label })
                OutlinedTextField(
                    value = dialog.name,
                    onValueChange = viewModel::setName,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionLabel("Icon")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SUGGESTED_ICONS.forEach { icon ->
                        FilterChip(selected = icon == dialog.icon, onClick = { viewModel.setIcon(icon) }, label = { Text(icon) })
                    }
                }
                ErrorText(dialog.error)
                if (dialog.id != null) {
                    TextButton(onClick = viewModel::archive, enabled = !dialog.saving) {
                        Text("Archive category", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = !dialog.saving) { Text(if (dialog.saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } },
    )
}
