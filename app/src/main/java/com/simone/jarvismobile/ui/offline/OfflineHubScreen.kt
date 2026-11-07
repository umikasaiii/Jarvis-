package com.simone.jarvismobile.ui.offline

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.background.AssistantTaskQueue
import com.simone.jarvismobile.knowledge.KnowledgeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Offline Hub: no cloud, no user account. Models are managed by the existing
 * LiteRT-LM models page. Source files are separated from Obsidian memory.
 * Kiwix ZIM content remains inside Kiwix; only explicitly copied excerpts
 * enter JARVIS until an actual libzim reader is implemented.
 */
@HiltViewModel
class OfflineHubViewModel @Inject constructor(
    private val knowledge: KnowledgeRepository,
    private val queue: AssistantTaskQueue,
) : ViewModel() {
    val status = knowledge.status
    val imported = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow("")

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        knowledge.ensureBuilt()
        imported.value = knowledge.importedDocuments()
    }

    fun chooseFolder(uri: Uri) = viewModelScope.launch {
        knowledge.setLibrary(uri)
        imported.value = knowledge.importedDocuments()
        message.value = "Cartella di documenti collegata per la ricerca offline."
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        message.value = "Importazione e indicizzazione in corso..."
        message.value = knowledge.importDocument(uri)
        imported.value = knowledge.importedDocuments()
    }

    fun saveExcerpt(title: String, article: String) = viewModelScope.launch {
        message.value = knowledge.saveExcerpt(title, article)
        imported.value = knowledge.importedDocuments()
    }

    fun reindex() = viewModelScope.launch {
        knowledge.rebuild()
        imported.value = knowledge.importedDocuments()
        message.value = "Indice aggiornato."
    }

    fun ask(question: String) = viewModelScope.launch {
        queue.enqueueResponse(question)
    }

    fun inform(text: String) { message.value = text }
}

private fun openInstalledApp(context: Context, packages: List<String>): Boolean {
    for (pkg in packages) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            context.startActivity(intent)
            return true
        }
    }
    return false
}

@Composable
fun OfflineHubScreen(
    onOpenModels: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: OfflineHubViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var question by remember { mutableStateOf("") }
    var excerptTitle by remember { mutableStateOf("") }
    var excerptText by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        uri -> if (uri != null) viewModel.importFile(uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        uri -> if (uri != null) viewModel.chooseFolder(uri)
    }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("JARVIS · OFFLINE HUB", style = MaterialTheme.typography.headlineSmall)
        Text(
            "AI e conoscenze sul dispositivo. Dopo aver importato i dati non serve Internet. " +
                "Memoria personale e archivio di riferimento rimangono separati.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · Cervello locale", style = MaterialTheme.typography.titleMedium)
                Text("Importa e carica un modello compatibile .litertlm nella sezione Modelli.")
                Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                    Text("Apri modelli AI")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2 · Archivio di riferimento", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (status.indexing) "Sto indicizzando..."
                    else if (status.configured) {
                        status.documentCount.toString() + " documenti · " +
                            status.passageCount + " passaggi interrogabili"
                    } else "Nessun documento importato",
                )
                Text(
                    "Formati: TXT, MD, HTML e CSV (max 4 MB per file). " +
                        "I file importati vengono copiati in uno spazio privato. " +
                        "Puoi anche collegare una cartella in sola lettura.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { filePicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Importa un file")
                }
                OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Collega cartella di documenti")
                }
                OutlinedButton(onClick = viewModel::reindex, modifier = Modifier.fillMaxWidth()) {
                    Text("Aggiorna indice locale")
                }
                if (imported.isNotEmpty()) {
                    Text("Documenti nell'archivio privato:", style = MaterialTheme.typography.titleSmall)
                    imported.take(15).forEach { name -> Text("• " + name, style = MaterialTheme.typography.bodySmall) }
                    if (imported.size > 15) Text("Altri " + (imported.size - 15) + " documenti")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3 · Interroga JARVIS", style = MaterialTheme.typography.titleMedium)
                Text(
                    "JARVIS cerca nei tuoi documenti e passa i risultati al modello locale. " +
                        "Verifica le fonti nelle risposte.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Cosa vuoi sapere?") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (question.isNotBlank()) {
                            viewModel.ask(question.trim())
                            question = ""
                            onOpenChat()
                        }
                    },
                    enabled = question.isNotBlank() && !status.indexing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Chiedi all'AI offline") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4 · Mappe e Wikipedia", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Organic Maps mantiene le mappe sul telefono. Kiwix apre archivi ZIM " +
                        "già scaricati. Al momento JARVIS NON ricerca direttamente dentro " +
                        "l'intero ZIM: puoi importare estratti di articoli qui sotto.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!openInstalledApp(context, listOf("app.organicmaps"))) {
                                viewModel.inform("Organic Maps non installata. Installala e scarica le mappe quando sei online.")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Organic Maps") }
                    OutlinedButton(
                        onClick = {
                            if (!openInstalledApp(context, listOf(
                                "org.kiwix.kiwixmobile",
                                "org.kiwix.kiwixmobile.standalone",
                            ))) {
                                viewModel.inform("Kiwix non installata. Installa l'app e scarica gli archivi ZIM quando sei online.")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Kiwix") }
                }
                OutlinedTextField(
                    value = excerptTitle,
                    onValueChange = { excerptTitle = it },
                    label = { Text("Titolo articolo / fonte") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = excerptText,
                    onValueChange = { excerptText = it },
                    label = { Text("Testo copiato da Kiwix o altre fonti") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { excerptText = clipboard.getText()?.text.orEmpty() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Incolla") }
                    Button(
                        onClick = {
                            viewModel.saveExcerpt(excerptTitle, excerptText)
                            excerptTitle = ""
                            excerptText = ""
                        },
                        enabled = excerptText.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Archivia") }
                }
            }
        }
        if (message.isNotBlank()) {
            Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        if (status.lastError != null) {
            Text("Errore indice: " + status.lastError, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Nessun server richiesto. Le mappe e i file ZIM restano nelle rispettive app; " +
                "non viene aggirato lo spazio privato di altre applicazioni.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
