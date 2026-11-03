package com.algopraxis.dhanniti.ui.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.algopraxis.dhanniti.data.reminders.RemindersRepository
import com.algopraxis.dhanniti.domain.ReminderSettings
import com.algopraxis.dhanniti.ui.components.EditorScaffold
import com.algopraxis.dhanniti.ui.components.ErrorText
import com.algopraxis.dhanniti.ui.components.LoadingOrError
import com.algopraxis.dhanniti.ui.components.SectionLabel
import com.algopraxis.dhanniti.ui.components.SwitchRow
import com.algopraxis.dhanniti.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalTime
import org.koin.compose.viewmodel.koinViewModel

/** The days before a due the app can remind on, nearest last. */
private val OFFSET_CHOICES = listOf(7, 5, 3, 2, 1, 0)

private fun offsetLabel(days: Int) = when (days) {
    0 -> "On the day"
    1 -> "1 day before"
    else -> "$days days before"
}

/** "09:00" → "9:00 am". */
internal fun formatTime(time: LocalTime): String {
    val hour = time.hour % 12
    return "${if (hour == 0) 12 else hour}:${time.minute.toString().padStart(2, '0')} ${if (time.hour < 12) "am" else "pm"}"
}

data class RemindersUiState(
    /** Null until loaded. */
    val form: ReminderSettings? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

private data class FormStatus(val draft: ReminderSettings? = null, val saving: Boolean = false, val saved: Boolean = false, val error: String? = null)

class RemindersViewModel(private val reminders: RemindersRepository) : ViewModel() {
    private val status = MutableStateFlow(FormStatus())

    val state: StateFlow<RemindersUiState> = combine(reminders.settings, status) { saved, status ->
        RemindersUiState(form = status.draft ?: saved, saving = status.saving, saved = status.saved, error = status.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemindersUiState())

    init {
        load()
    }

    fun load() {
        status.update { it.copy(error = null) }
        launchCatching(onError = { message -> status.update { it.copy(error = message) } }) { reminders.refresh() }
    }

    private fun edit(change: (ReminderSettings) -> ReminderSettings) {
        val current = state.value.form ?: return
        status.update { it.copy(draft = change(current), saved = false, error = null) }
    }

    fun setEnabled(value: Boolean) = edit { it.copy(enabled = value) }
    fun setOverdueDaily(value: Boolean) = edit { it.copy(overdueDaily = value) }
    fun setTime(time: LocalTime) = edit { it.copy(timeOfDay = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}") }
    fun toggleOffset(days: Int) = edit {
        it.copy(offsetsDays = (if (days in it.offsetsDays) it.offsetsDays - days else it.offsetsDays + days).sortedDescending())
    }

    fun save() {
        val draft = status.value.draft ?: return
        if (status.value.saving) return
        status.update { it.copy(saving = true, error = null) }
        launchCatching(onError = { message -> status.update { it.copy(saving = false, error = message) } }) {
            // Saving updates the repository, and the coordinator replans every reminder from it.
            reminders.save(draft)
            status.update { it.copy(draft = null, saving = false, saved = true) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemindersScreen(onBack: () -> Unit, viewModel: RemindersViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form

    EditorScaffold(title = "Reminders", onBack = onBack) { padding ->
        if (form == null) {
            LoadingOrError(state.error, onRetry = viewModel::load, modifier = Modifier.padding(padding))
            return@EditorScaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SwitchRow(
                "Remind me about dues",
                "Notifications for EMIs, card bills and recurring payments. They work offline.",
                form.enabled,
                viewModel::setEnabled,
            )
            if (form.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("When")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OFFSET_CHOICES.forEach { days ->
                            FilterChip(
                                selected = days in form.offsetsDays,
                                onClick = { viewModel.toggleOffset(days) },
                                label = { Text(offsetLabel(days)) },
                            )
                        }
                    }
                }
                TimeField(form.time, viewModel::setTime, label = "At")
                SwitchRow(
                    "Remind me daily while overdue",
                    "Until it's marked paid or skipped",
                    form.overdueDaily,
                    viewModel::setOverdueDaily,
                )
                Text(
                    "Each reminder has Mark paid and Snooze 1 day buttons. Mark paid pays the full amount today from the due's " +
                        "account; to pay a different amount, open the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ErrorText(state.error)
            if (state.saved) Text("✓ Saved. Reminders are rescheduled.", color = MaterialTheme.colorScheme.primary)

            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        }
    }
}

/** Read-only field that opens a time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(time: LocalTime, onTimeChange: (LocalTime) -> Unit, label: String) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value = formatTime(time), onValueChange = {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        // A read-only text field swallows clicks, so catch them on an overlay.
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val picker = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            text = { TimePicker(state = picker) },
            confirmButton = {
                TextButton(onClick = { onTimeChange(LocalTime(picker.hour, picker.minute)); open = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
