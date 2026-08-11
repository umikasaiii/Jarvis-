package com.simone.jarvismobile.ui.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.memory.MemoryCategories
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val UNCATEGORIZED = "Senza categoria"
private val MUTED = Color(0xFF7C8B95)

/**
 * Memoria, laid out like a phone notes app: a title, colour-tagged note tiles in
 * a two-column grid grouped by category, and a "+" button that opens an editor.
 * Everything is stored on the device; an Obsidian vault is an optional mirror.
 */
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

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }

    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::onVaultPicked) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Memoria", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${records.size} ricordi · tutto sul dispositivo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MUTED,
                )
            }

            message?.let { Text(it, color = Color(0xFF3FD8F0), fontWeight = FontWeight.Medium) }
            status.lastError?.let { Text("Errore: $it", color = MaterialTheme.colorScheme.error) }

            if (records.any { it.category.isBlank() }) {
                OutlinedButton(onClick = viewModel::reclassify, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Classificazione…" else "Classifica con l'AI")
                }
            }

            // Notes grouped by category. The four running lists are always shown,
            // then the AI categories alphabetical, then the not-yet-sorted bucket.
            val byCategory = records.groupBy { it.category.ifBlank { UNCATEGORIZED } }
            val listCats = MemoryCategories.LISTS
            val otherCats = (byCategory.keys - listCats.toSet() - UNCATEGORIZED).sorted()
            val ordered = listCats + otherCats +
                listOfNotNull(UNCATEGORIZED.takeIf(byCategory::containsKey))
            ordered.forEach { category ->
                val list = byCategory[category].orEmpty().sortedByDescending { it.updatedAt }
                CategoryHeader(category, accentForCategory(category), list.size)
                if (list.isEmpty()) {
                    Text("Ancora niente qui.", style = MaterialTheme.typography.bodySmall, color = MUTED)
                } else {
                    NoteGrid(list, enabled = !busy, onOpen = { editing = it })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // Short-term recap — compact, below the notes.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Memoria breve", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Privata e temporanea: riassume conversazioni lunghe. " +
                            "Si cancella con “Nuova conversazione”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MUTED,
                    )
                    if (shortTerm.isEmpty) {
                        Text("Nessun riepilogo temporaneo.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        shortTerm.facts.forEach { Text("• $it") }
                        StructuredFields(shortTerm.topics, shortTerm.people, shortTerm.dates)
                        OutlinedButton(onClick = viewModel::clearTemporary, enabled = !busy) {
                            Text("Cancella memoria breve")
                        }
                    }
                }
            }

            // Optional Obsidian mirror — the memory works fully without it.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vault Obsidian (facoltativo)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (status.configured) "Collegato: ${vaultName ?: "—"}" else "Nessun vault collegato",
                        style = MaterialTheme.typography.bodySmall,
                        color = MUTED,
                    )
                    Button(
                        onClick = { pickVault.launch(null) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (status.configured) "Cambia cartella vault" else "Collega un vault") }
                    if (status.configured) {
                        OutlinedButton(onClick = viewModel::reindex, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Sincronizzazione…" else "Sincronizza da Obsidian")
                        }
                        OutlinedButton(
                            onClick = viewModel::disconnect,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Disconnetti vault") }
                    }
                }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Indietro") }
            Text(
                "Tutto resta sul dispositivo · nessun salvataggio segreto",
                style = MaterialTheme.typography.bodySmall,
                color = MUTED,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showAdd) {
        NoteDialog(
            title = "Nuovo ricordo",
            initialText = "",
            initialKind = MemoryKind.PERMANENT,
            initialCategory = "",
            allowTemporary = true,
            showCategory = false,
            showDelete = false,
            enabled = !busy,
            onDismiss = { showAdd = false },
            onSave = { text, kind, _ -> viewModel.add(text, kind); showAdd = false },
            onDelete = {},
        )
    }

    editing?.let { rec ->
        NoteDialog(
            title = "Ricordo",
            initialText = rec.text,
            initialKind = rec.kind,
            initialCategory = rec.category,
            allowTemporary = false,
            showCategory = true,
            showDelete = true,
            enabled = !busy,
            topics = rec.topics,
            people = rec.people,
            dates = rec.dates,
            onDismiss = { editing = null },
            onSave = { text, kind, category ->
                viewModel.update(rec.id, text, kind)
                if (category != rec.category) viewModel.setCategory(rec.id, category)
                editing = null
            },
            onDelete = { viewModel.delete(rec.id); editing = null },
        )
    }
}

/** A small coloured dot + name + count, like a notes-app section label. */
@Composable
private fun CategoryHeader(name: String, accent: Color, count: Int) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (count > 0) Text("$count", style = MaterialTheme.typography.labelMedium, color = MUTED)
    }
}

/** Two-column note grid built from simple chunked rows (no extra grid deps). */
@Composable
private fun NoteGrid(records: List<MemoryRecord>, enabled: Boolean, onOpen: (MemoryRecord) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        records.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { rec ->
                    Box(Modifier.weight(1f)) { NoteTile(rec, enabled, onOpen) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A colour-tinted note tile showing the note text and its date; tap to edit. */
@Composable
private fun NoteTile(record: MemoryRecord, enabled: Boolean, onOpen: (MemoryRecord) -> Unit) {
    val accent = if (record.kind == MemoryKind.SENSITIVE) {
        MaterialTheme.colorScheme.error
    } else {
        accentForCategory(record.category)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .clickable(enabled = enabled) { onOpen(record) },
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.16f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                record.text.trim() + if (record.kind == MemoryKind.SENSITIVE) "  🔒" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            formatNoteDate(record.updatedAt).ifBlank { null }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MUTED)
            }
        }
    }
}

/** The add/edit editor, shown as a dialog rather than a big inline form. */
@Composable
private fun NoteDialog(
    title: String,
    initialText: String,
    initialKind: MemoryKind,
    initialCategory: String,
    allowTemporary: Boolean,
    showCategory: Boolean,
    showDelete: Boolean,
    enabled: Boolean,
    topics: List<String> = emptyList(),
    people: List<String> = emptyList(),
    dates: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (String, MemoryKind, String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var kind by remember { mutableStateOf(initialKind) }
    var category by remember { mutableStateOf(initialCategory) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Testo del ricordo") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                KindSelector(kind, onSelect = { kind = it }, includeTemporary = allowTemporary)
                if (kind == MemoryKind.SENSITIVE) {
                    Text(
                        "Marcato come sensibile. Password, PIN, OTP e token non vengono salvati.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (showCategory) {
                    CategorySelector(category, enabled) { category = it }
                }
                StructuredFields(topics, people, dates)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onSave(text, kind, category) },
                enabled = enabled && text.isNotBlank(),
            ) { Text("Salva") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showDelete) {
                    TextButton(
                        onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                        enabled = enabled,
                    ) {
                        Text(
                            if (confirmDelete) "Conferma" else "Elimina",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text("Annulla") }
            }
        },
    )
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

/** A tap-to-change category chip, over the canonical list. */
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
