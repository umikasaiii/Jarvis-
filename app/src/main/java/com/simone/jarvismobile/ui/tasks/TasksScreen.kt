package com.simone.jarvismobile.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.background.AssistantTask
import com.simone.jarvismobile.background.AssistantTaskStatus

@Composable
fun TasksScreen(viewModel: TasksViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Attività JARVIS", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Le risposte continuano in background. Puoi annullare un lavoro attivo o riprovare dopo un errore.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (tasks.isEmpty()) {
            Text("Nessuna attività recente.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task, onCancel = { viewModel.cancel(task.id) }, onRetry = { viewModel.retry(task.id) })
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: AssistantTask, onCancel: () -> Unit, onRetry: () -> Unit) {
    val active = task.parsedStatus in setOf(
        AssistantTaskStatus.QUEUED,
        AssistantTaskStatus.LOADING_MODEL,
        AssistantTaskStatus.UNDERSTANDING,
        AssistantTaskStatus.RETRIEVING_MEMORY,
        AssistantTaskStatus.GENERATING,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.input, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            Text(statusLabel(task.parsedStatus), style = MaterialTheme.typography.labelMedium)
            if (active) {
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            task.output?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when {
                    active -> OutlinedButton(onClick = onCancel) { Text("Annulla") }
                    task.parsedStatus == AssistantTaskStatus.FAILED ->
                        OutlinedButton(onClick = onRetry) { Text("Riprova") }
                }
            }
        }
    }
}

private fun statusLabel(status: AssistantTaskStatus): String = when (status) {
    AssistantTaskStatus.QUEUED -> "In attesa"
    AssistantTaskStatus.LOADING_MODEL -> "Caricamento modello"
    AssistantTaskStatus.UNDERSTANDING -> "Comprensione"
    AssistantTaskStatus.RETRIEVING_MEMORY -> "Ricerca memoria"
    AssistantTaskStatus.GENERATING -> "Generazione risposta"
    AssistantTaskStatus.COMPLETED -> "Completata"
    AssistantTaskStatus.FAILED -> "Errore"
    AssistantTaskStatus.CANCELLED -> "Annullata"
}
