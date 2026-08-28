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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.memory.MemoryCategories
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord
import com.simone.jarvismobile.core.memory.ShortTermMemorySnapshot
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val UNCATEGORIZED = "Senza categoria"
private val MUTED = Color(0xFF7C8B95)
private val INK = Color(0xFFE3EFF5)
// The brand accent (§ Impostazioni › Temi) — same convention as Agenda/Archivio.
private val Cyan: Color
    @Composable get() = LocalJarvisPalette.current.accent

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
    var showOptions by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::onVaultPicked) }

    // Full-screen editor, like a phone notes app rather than a cramped dialog
    // (§ richiesta esplicita dell'utente, riferimento Note del telefono) — an
    // early return keeps it out of the main list's Box entirely instead of
    // stacking it as an overlay.
    if (showAdd) {
        MemoryNoteEditorScreen(
            title = "Nuovo ricordo",
            initialText = "",
            initialKind = MemoryKind.PERMANENT,
            initialCategory = "",
            allowTemporary = true,
            showDelete = false,
            enabled = !busy,
            onDismiss = { showAdd = false },
            onSave = { text, kind, category -> viewModel.add(text, kind, category); showAdd = false },
            onDelete = {},
        )
        return
    }
    editing?.let { rec ->
        MemoryNoteEditorScreen(
            title = "Ricordo",
            initialText = rec.text,
            initialKind = rec.kind,
            initialCategory = rec.category,
            allowTemporary = false,
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
        return
    }

    val accent = Cyan
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E)))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Just notes up top, like a phone's Notes app — everything that
            // isn't a note (the short-term recap, the optional Obsidian mirror)
            // moved behind the "⋮" button instead of being mixed into the feed.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Memoria", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accent)
                    Text(
                        "${records.size} ricordi · tutto sul dispositivo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MUTED,
                    )
                }
                IconButton(onClick = { showOptions = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Altre opzioni", tint = INK)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Cerca nei ricordi…", color = MUTED) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MUTED) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancella ricerca", tint = MUTED)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = INK),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent.copy(alpha = 0.7f),
                    unfocusedBorderColor = MUTED.copy(alpha = 0.4f),
                    focusedContainerColor = Color(0x330A1826),
                    unfocusedContainerColor = Color(0x330A1826),
                    cursorColor = accent,
                    focusedTextColor = INK,
                    unfocusedTextColor = INK,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            message?.let { Text(it, color = accent, fontWeight = FontWeight.Medium) }
            status.lastError?.let { Text("Errore: $it", color = MaterialTheme.colorScheme.error) }

            if (records.any { it.category.isBlank() }) {
                OutlinedButton(onClick = viewModel::reclassify, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Classificazione…" else "Classifica con l'AI")
                }
            }

            // Notes grouped by category. The four running lists are always shown,
            // then the AI categories alphabetical, then the not-yet-sorted bucket.
            // Each section is collapsible — its open/closed state is kept per name.
            val filtered = if (query.isBlank()) {
                records
            } else {
                records.filter { it.text.contains(query, ignoreCase = true) }
            }
            val collapsed = remember { mutableStateMapOf<String, Boolean>() }
            val byCategory = filtered.groupBy { it.category.ifBlank { UNCATEGORIZED } }
            val listCats = MemoryCategories.LISTS
            val otherCats = (byCategory.keys - listCats.toSet() - UNCATEGORIZED).sorted()
            val ordered = listCats + otherCats +
                listOfNotNull(UNCATEGORIZED.takeIf(byCategory::containsKey))
            if (query.isNotBlank() && filtered.isEmpty()) {
                Text("Nessun ricordo trovato per «$query».", style = MaterialTheme.typography.bodySmall, color = MUTED)
            }
            ordered.forEach { category ->
                val list = byCategory[category].orEmpty().sortedByDescending { it.updatedAt }
                val open = collapsed[category] != true
                CategoryHeader(
                    name = category,
                    accent = accentForCategory(category),
                    count = list.size,
                    expanded = open,
                    onToggle = { collapsed[category] = open },
                )
                if (open) {
                    if (list.isEmpty()) {
                        Text("Ancora niente qui.", style = MaterialTheme.typography.bodySmall, color = MUTED)
                    } else {
                        NoteGrid(list, enabled = !busy, onOpen = { editing = it })
                    }
                }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Indietro") }
            Text(
                "Tutto resta sul dispositivo · nessun salvataggio segreto",
                style = MaterialTheme.typography.bodySmall,
                color = MUTED,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 88.dp), // clears the floating "+" below
            )
        }

        IconButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(58.dp)
                .clip(CircleShape)
                .background(accent),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Nuovo ricordo", tint = Color(0xFF04121A))
        }
    }

    if (showOptions) {
        MemorySettingsDialog(
            status = status,
            vaultName = vaultName,
            shortTerm = shortTerm,
            busy = busy,
            onDismiss = { showOptions = false },
            onPickVault = { pickVault.launch(null) },
            onReindex = viewModel::reindex,
            onDisconnect = viewModel::disconnect,
            onClearTemporary = viewModel::clearTemporary,
        )
    }

}

/**
 * "⋮" on the main screen: the short-term recap and the optional Obsidian mirror
 * — settings-shaped things, not notes — used to sit inline at the bottom of the
 * notes feed. A classic phone Notes app keeps its screen to just notes and tucks
 * everything else away; this dialog is that tuck-away, with the exact same
 * content and callbacks as before, nothing removed.
 */
@Composable
private fun MemorySettingsDialog(
    status: MemoryIndex.Status,
    vaultName: String?,
    shortTerm: ShortTermMemorySnapshot,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPickVault: () -> Unit,
    onReindex: () -> Unit,
    onDisconnect: () -> Unit,
    onClearTemporary: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Altre opzioni") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Short-term recap.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        OutlinedButton(onClick = onClearTemporary, enabled = !busy) {
                            Text("Cancella memoria breve")
                        }
                    }
                }

                HorizontalDivider()

                // Optional Obsidian mirror — the memory works fully without it.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vault Obsidian (facoltativo)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (status.configured) "Collegato: ${vaultName ?: "—"}" else "Nessun vault collegato",
                        style = MaterialTheme.typography.bodySmall,
                        color = MUTED,
                    )
                    Button(onClick = onPickVault, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (status.configured) "Cambia cartella vault" else "Collega un vault")
                    }
                    if (status.configured) {
                        OutlinedButton(onClick = onReindex, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "Sincronizzazione…" else "Sincronizza da Obsidian")
                        }
                        OutlinedButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Disconnetti vault")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

/**
 * A tappable notes-app section label: a coloured dot, the name, its count, and a
 * chevron that flips as the section is opened or closed.
 */
@Composable
private fun CategoryHeader(
    name: String,
    accent: Color,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = INK)
        if (count > 0) Text("$count", style = MaterialTheme.typography.labelMedium, color = MUTED)
        Spacer(Modifier.weight(1f))
        Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium, color = MUTED)
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                record.text.trim() + if (record.kind == MemoryKind.SENSITIVE) "  🔒" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = INK,
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

/**
 * The add/edit editor, full-screen like a phone notes app instead of a small
 * dialog (§ richiesta esplicita dell'utente: "identica anche nel funzionamento
 * ... a note in foto") — a big title-less canvas plus a formatting toolbar
 * that inserts Markdown at the cursor/selection (bold/italic/bullet/numbered).
 * Markdown, not opaque rich-text spans, because [MemoryRecord.text] is plain
 * text and the Obsidian vault (`JARVIS/Memoria.md`) is the human-readable
 * source of truth (see CLAUDE.md) — the same syntax round-trips there exactly
 * as typed, instead of a second, app-only formatting format nothing else reads.
 */
@Composable
private fun MemoryNoteEditorScreen(
    title: String,
    initialText: String,
    initialKind: MemoryKind,
    initialCategory: String,
    allowTemporary: Boolean,
    showDelete: Boolean,
    enabled: Boolean,
    topics: List<String> = emptyList(),
    people: List<String> = emptyList(),
    dates: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (String, MemoryKind, String) -> Unit,
    onDelete: () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(initialText)) }
    var kind by remember { mutableStateOf(initialKind) }
    var category by remember { mutableStateOf(initialCategory) }
    var confirmDelete by remember { mutableStateOf(false) }
    val accent = Cyan

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E))))
            .imePadding(),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Annulla", tint = INK)
                }
                Text(
                    title,
                    color = INK,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
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
                TextButton(
                    onClick = { if (field.text.isNotBlank()) onSave(field.text, kind, category) },
                    enabled = enabled && field.text.isNotBlank(),
                ) { Text("Salva", color = if (field.text.isNotBlank()) accent else MUTED) }
            }
            Spacer(Modifier.size(12.dp))
            KindSelector(kind, onSelect = { kind = it }, includeTemporary = allowTemporary)
            if (kind == MemoryKind.SENSITIVE) {
                Text(
                    "Marcato come sensibile. Password, PIN, OTP e token non vengono salvati.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            CategorySelector(category, enabled) { category = it }
            StructuredFields(topics, people, dates)
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                placeholder = { Text("Scrivi qui…", color = MUTED) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = INK),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = accent,
                    focusedTextColor = INK,
                    unfocusedTextColor = INK,
                ),
            )
            FormattingToolbar(
                onBold = { field = wrapSelection(field, "**") },
                onItalic = { field = wrapSelection(field, "*") },
                onBullet = { field = prefixLine(field, "- ") },
                onNumbered = { field = prefixLine(field, "1. ") },
            )
            Spacer(Modifier.size(24.dp))
        }
    }
}

/**
 * Bold/italic/bullet/numbered — plain text glyphs rather than Material's
 * "extended" icon set (FormatBold/FormatItalic/…), which lives in a separate
 * artifact this project doesn't otherwise depend on and couldn't be verified
 * against a compiler in this environment; text glyphs match the project's
 * existing convention (the "▾"/"▸" chevrons above, emoji document glyphs in
 * Archivio) and carry zero dependency risk.
 */
@Composable
private fun FormattingToolbar(onBold: () -> Unit, onItalic: () -> Unit, onBullet: () -> Unit, onNumbered: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphButton(onClick = onBold) { Text("B", color = INK, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        GlyphButton(onClick = onItalic) { Text("I", color = INK, fontStyle = FontStyle.Italic, fontSize = 16.sp) }
        GlyphButton(onClick = onBullet) { Text("•", color = INK, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        GlyphButton(onClick = onNumbered) { Text("1.", color = INK, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
}

@Composable
private fun GlyphButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x330A1826))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Wraps the selection (or inserts an empty pair at the cursor) in [marker], e.g. "**bold**". */
private fun wrapSelection(value: TextFieldValue, marker: String): TextFieldValue {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    val newText = text.substring(0, start) + marker + text.substring(start, end) + marker + text.substring(end)
    val newCursor = if (start == end) start + marker.length else end + marker.length * 2
    return value.copy(text = newText, selection = TextRange(newCursor))
}

/** Inserts [prefix] at the start of the line the cursor is on, e.g. "- " or "1. ". */
private fun prefixLine(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val cursor = value.selection.min
    val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
    val newText = text.substring(0, lineStart) + prefix + text.substring(lineStart)
    return value.copy(text = newText, selection = TextRange(cursor + prefix.length))
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
