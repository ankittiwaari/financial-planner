package com.algopraxis.dhanniti.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.algopraxis.dhanniti.data.sync.SyncRepository
import com.algopraxis.dhanniti.data.sync.SyncStatus
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.domain.today
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

/**
 * A strip shown while offline, while changes wait to be sent, or when the server refused one. Hidden when all is well.
 */
@Composable
fun SyncBanner(sync: SyncRepository = koinInject()) {
    val status by sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var reviewing by remember { mutableStateOf(false) }
    val message = bannerMessage(status) ?: return

    Surface(
        color = if (status.failed.isNotEmpty()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            when {
                status.failed.isNotEmpty() -> TextButton(onClick = { reviewing = true }) { Text("Review") }
                !status.online -> TextButton(onClick = { scope.launch { sync.sync() } }) { Text("Retry") }
            }
        }
    }

    if (reviewing && status.failed.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { reviewing = false },
            title = { Text("Couldn't save") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("The server refused these changes made offline. Discard them and enter them again if needed.")
                    status.failed.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.change.summary, style = MaterialTheme.typography.bodyMedium)
                                Text(item.lastError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { scope.launch { sync.discard(item) } }) { Text("Discard") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reviewing = false }) { Text("Close") } },
        )
    }
}

private fun bannerMessage(status: SyncStatus): String? {
    val waiting = when (status.pending) {
        0 -> null
        1 -> "1 change waiting to sync"
        else -> "${status.pending} changes waiting to sync"
    }
    return when {
        status.failed.isNotEmpty() -> if (status.failed.size == 1) "1 offline change couldn't be saved" else "${status.failed.size} offline changes couldn't be saved"
        !status.online -> listOfNotNull("Offline", status.lastSyncedAt?.let { "showing data from ${formatSyncedAt(it)}" }, waiting).joinToString(" · ")
        else -> waiting
    }
}

/** "Today 4:30 pm", "Yesterday 9:05 am", "1 Nov 2026 6:00 pm". */
private fun formatSyncedAt(instant: kotlin.time.Instant): String {
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = local.hour % 12
    val time = "${if (hour == 0) 12 else hour}:${local.minute.toString().padStart(2, '0')} ${if (local.hour < 12) "am" else "pm"}"
    return "${local.date.formatRelative(today())} $time"
}
