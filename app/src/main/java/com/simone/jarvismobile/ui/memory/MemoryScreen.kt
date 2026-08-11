package com.simone.jarvismobile.ui.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.simone.jarvismobile.core.memory.MemoryCategories
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord

private const val UNCATEGORIZED = "Senza categoria"

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

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Archivio ricordi", style = MaterialTheme.typography.titleMedium)
            if (records.any { it.category.isBlank() }) {
                OutlinedButton(onClick = viewModel::reclassify, enabled = !busy) {
                    Text(if (busy) "…" else "Classifica con l'AI")
                }
            }
        }
        run {
            // Notes-app style, grouped by category. The four running "lists"
            // (Da guardare / Da visitare / Da fare / Da comprare) are ALWAYS shown,
            // even empty, so they stay ready to fill; then the other AI categories
            // alphabetical, and the not-yet-classified bucket last. Newest first.
            val byCategory = records.groupBy { it.category.ifBlank { UNCATEGORIZED } }
            val listCats = MemoryCategories.LISTS
            val otherCats = (byCategory.keys - listCats.toSet() - UNCATEGORIZED).sorted()
            val ordered = listCats + otherCats +
                listOfNotNull(UNCATEGORIZED.takeIf(byCategory::containsKey))
            ordered.forEach { category ->
                val list = byCategory[category].orEmpty().sortedByDescending { it.updatedAt }
                Text(
                    category,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF3FD8F0),
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (list.isEmpty()) {
                    Text("Ancora niente qui.", style = MaterialTheme.typography.bodySmall)
                } else {
                    list.forEach { record ->
                        MemoryRecordCard(
                            record = record,
                            enabled = !busy,
                            onSave = viewModel::update,
                            onSetCategory = viewModel::setCategory,
                            onDelete = viewModel::delete,
                        )
                    }
                }
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

/**
 * A memory shown like a phone-notes card: a coloured accent bar, a one-line
 * title, and a date + preview. Tapping it expands the full editor (text, type,
 * category, delete). Collapsed by default so the archive reads like a note list.
 */
@Composable
private fun MemoryRecordCard(
    record: MemoryRecord,
    enabled: Boolean,
    onSave: (String, String, MemoryKind) -> Unit,
    onSetCategory: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by remember(record.id) { mutableStateOf(false) }
    var text by remember(record.id, record.updatedAt) { mutableStateOf(record.text) }
    var kind by remember(record.id, record.updatedAt) { mutableStateOf(record.kind) }
    var confirmDelete by remember(record.id) { mutableStateOf(false) }

    val accent = if (record.kind == MemoryKind.SENSITIVE) {
        MaterialTheme.colorScheme.error
    } else {
        accentForCategory(record.category)
    }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable { expanded = !expanded },
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Column(
                Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    noteTitle(record.text) + if (record.kind == MemoryKind.SENSITIVE) "  🔒" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(formatNoteDate(record.updatedAt).ifBlank { null }, notePreview(record.text))
                        .joinToString("   "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7C8B95),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                KindSelector(kind, onSelect = { kind = it }, includeTemporary = false)
                CategorySelector(
                    current = record.category,
                    enabled = enabled,
                    onSelect = { onSetCategory(record.id, it) },
                )
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
}

/** First line, trimmed to a note-title length. */
private fun noteTitle(text: String): String {
    val firstLine = text.trim().lineSequence().firstOrNull()?.trim().orEmpty()
    return when {
        firstLine.isBlank() -> "(vuoto)"
        firstLine.length <= 48 -> firstLine
        else -> firstLine.take(48).trimEnd() + "…"
    }
}

/** The remainder of the note after the title, as a one-line preview, or null. */
private fun notePreview(text: String): String? {
    val clean = text.trim().replace(Regex("""\s+"""), " ")
    return if (clean.length <= 48) null else clean.substring(48).trim().take(70).ifBlank { null }
}

private fun formatNoteDate(ms: Long): String =
    if (ms <= 0L) "" else SimpleDateFormat("d MMM", Locale.ITALIAN).format(Date(ms))

/** A stable accent colour per category, so notes read like colour-tagged cards. */
private fun accentForCategory(category: String): Color {
    val palette = listOf(
        Color(0xFF3FD8F0), Color(0xFF7C5CFF), Color(0xFF2ECC71), Color(0xFFF1C40F),
        Color(0xFFE67E22), Color(0xFFEB5AA6), Color(0xFF12D9FF), Color(0xFF9B59B6),
    )
    val key = category.ifBlank { UNCATEGORIZED }
    return palette[(key.hashCode() and 0x7fffffff) % palette.size]
}

/** A tap-to-change category chip on a memory card, over the canonical list. */
@Composable
private fun CategorySelector(current: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("Categoria: " + current.ifBlank { "Senza categoria" })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MemoryCategories.CANONICAL.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category) },
                    onClick = { open = false; onSelect(category) },
                )
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
