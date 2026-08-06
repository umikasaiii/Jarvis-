package com.simone.jarvismobile.ui.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord

/** Memory V2: temporary recap plus editable, structured Obsidian memories. */
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val vaultName by viewModel.vaultName.collectAsStateWithLifecycle()
    val shortTerm by viewModel.shortTerm.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var newText by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(MemoryKind.PERMANENT) }

    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::onVaultPicked) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Memoria V2", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Vault: ${vaultName ?: "—"}", style = MaterialTheme.typography.titleMedium)
                Text("Stato: ${statusLabel(status)}")
                Text(
                    "${status.noteCount} note · ${status.chunkCount} frammenti · ${records.size} ricordi",
                    style = MaterialTheme.typography.bodySmall,
                )
                status.lastError?.let { Text("Errore: $it", color = MaterialTheme.colorScheme.error) }
            }
        }

        Button(
            onClick = { pickVault.launch(null) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (status.configured) "Cambia cartella vault" else "Scegli cartella vault") }

        if (status.configured) {
            OutlinedButton(onClick = viewModel::reindex, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Sincronizzazione…" else "Sincronizza da Obsidian")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Memoria breve", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Privata e temporanea: riassume conversazioni lunghe senza una seconda generazione AI. " +
                        "Si cancella con “Nuova conversazione”.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (shortTerm.isEmpty) {
                    Text("Nessun riepilogo temporaneo.")
                } else {
                    shortTerm.facts.forEach { Text("• $it") }
                    StructuredFields(shortTerm.topics, shortTerm.people, shortTerm.dates)
                    OutlinedButton(onClick = viewModel::clearTemporary, enabled = !busy) {
                        Text("Cancella memoria breve")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Nuovo ricordo", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    label = { Text("Testo esatto da ricordare") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                KindSelector(newKind, onSelect = { newKind = it }, includeTemporary = true)
                if (newKind == MemoryKind.SENSITIVE) {
                    Text(
                        "Il contenuto sarà marcato sensibile nel vault. Password, PIN, OTP e token non vengono salvati.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { viewModel.add(newText, newKind); newText = "" },
                    enabled = newText.isNotBlank() && !busy &&
                        (newKind == MemoryKind.TEMPORARY || status.configured),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (newKind == MemoryKind.TEMPORARY) "Aggiungi alla conversazione" else "Salva nel vault") }
            }
        }

        Text("Ricordi permanenti", style = MaterialTheme.typography.titleMedium)
        if (records.isEmpty()) {
            Text("Nessun ricordo permanente nel vault.", style = MaterialTheme.typography.bodyMedium)
        } else {
            records.forEach { record ->
                MemoryRecordCard(
                    record = record,
                    enabled = !busy,
                    onSave = viewModel::update,
                    onDelete = viewModel::delete,
                )
            }
        }

        message?.let { Text(it, color = Color(0xFF3FD8F0), fontWeight = FontWeight.Medium) }

        if (status.configured) {
            OutlinedButton(onClick = viewModel::disconnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnetti vault")
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Indietro") }

        Text(
            "Tutto resta sul dispositivo · Obsidian è la fonte di verità · nessun salvataggio segreto",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MemoryRecordCard(
    record: MemoryRecord,
    enabled: Boolean,
    onSave: (String, String, MemoryKind) -> Unit,
    onDelete: (String) -> Unit,
) {
    var text by remember(record.id, record.updatedAt) { mutableStateOf(record.text) }
    var kind by remember(record.id, record.updatedAt) { mutableStateOf(record.kind) }
    var confirmDelete by remember(record.id) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (record.kind == MemoryKind.SENSITIVE) "Sensibile" else "Permanente",
                color = if (record.kind == MemoryKind.SENSITIVE) MaterialTheme.colorScheme.error else Color(0xFF3FD8F0),
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            KindSelector(kind, onSelect = { kind = it }, includeTemporary = false)
            StructuredFields(record.topics, record.people, record.dates)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(record.id, text, kind) },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Salva") }
                OutlinedButton(
                    onClick = {
                        if (confirmDelete) onDelete(record.id) else confirmDelete = true
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text(if (confirmDelete) "Conferma elimina" else "Elimina") }
            }
        }
    }
}

@Composable
private fun KindSelector(selected: MemoryKind, onSelect: (MemoryKind) -> Unit, includeTemporary: Boolean) {
    val kinds = if (includeTemporary) MemoryKind.entries else listOf(MemoryKind.PERMANENT, MemoryKind.SENSITIVE)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        kinds.forEach { kind ->
            if (kind == selected) {
                Button(onClick = { onSelect(kind) }, modifier = Modifier.weight(1f)) { Text(kind.label()) }
            } else {
                OutlinedButton(onClick = { onSelect(kind) }, modifier = Modifier.weight(1f)) { Text(kind.label()) }
            }
        }
    }
}

@Composable
private fun StructuredFields(topics: List<String>, people: List<String>, dates: List<String>) {
    if (topics.isEmpty() && people.isEmpty() && dates.isEmpty()) return
    HorizontalDivider()
    if (topics.isNotEmpty()) Text("Argomenti: ${topics.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
    if (people.isNotEmpty()) Text("Persone: ${people.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
    if (dates.isNotEmpty()) Text("Date: ${dates.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
}

private fun MemoryKind.label(): String = when (this) {
    MemoryKind.TEMPORARY -> "Temporaneo"
    MemoryKind.PERMANENT -> "Permanente"
    MemoryKind.SENSITIVE -> "Sensibile"
}

private fun statusLabel(s: com.simone.jarvismobile.memory.MemoryIndex.Status): String = when {
    s.building -> "Sincronizzazione in corso…"
    !s.configured -> "Nessun vault collegato"
    s.chunkCount > 0 -> "Pronta"
    else -> "Collegato (vuoto)"
}
