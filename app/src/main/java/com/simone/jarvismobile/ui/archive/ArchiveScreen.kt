package com.simone.jarvismobile.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.archive.ARCHIVE_UNFILED_FOLDER
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveList
import com.simone.jarvismobile.core.archive.ArchiveListItem
import com.simone.jarvismobile.core.archive.ArchiveStatus
import com.simone.jarvismobile.core.archive.ListItemStatus
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// Same palette convention as AgendaScreen (§ Impostazioni › Temi) — kept local
// rather than shared, same pattern already used across the app's plain-Compose
// screens (Agenda, Automazioni, Comandi…).
private val Cyan: Color
    @Composable get() = LocalJarvisPalette.current.accent
private val Ink = Color(0xFFE3EFF5)
private val Muted = Color(0xFF7C8B95)
private val Gold = Color(0xFFF3C34C)
private val CardBg = Color(0x660A1826)

private enum class ArchiveTab(val label: String) {
    ALL("Tutto"), NOTES("Appunti"), LISTS("Liste"), TODO("TODO"), WATCH("Da vedere"), DOCUMENTS("Documenti"),
}

/**
 * "Archivio" (spec §2): the Personal Archive's own home screen — Appunti,
 * Liste (spesa + personalizzate), TODO, Da vedere, Documenti/Foto, and a
 * merged "Tutto" view. TODO and Documenti/Foto are deliberately quick-links
 * into the existing, already-complete Attività ([com.simone.jarvismobile.ui.agenda.AgendaScreen])
 * and Archivio documenti ([com.simone.jarvismobile.ui.documents.DocumentArchiveScreen])
 * screens rather than a second implementation of either — see docs/PERSONAL_ARCHIVE.md.
 *
 * Appunti (§ richiesta esplicita dell'utente, riferimento l'app Note del
 * telefono) works like a real notes app now: a folder chip row, an "In primo
 * piano" section for pinned notes, chronological month grouping below, and a
 * full-screen editor instead of a small dialog — all in JARVIS's own visual
 * language, not a copy of the reference app's branding.
 */
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenDocuments: () -> Unit,
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val allRows by viewModel.allRows.collectAsStateWithLifecycle()
    val customLists by viewModel.customLists.collectAsStateWithLifecycle()
    val shoppingItems by viewModel.shoppingItems.collectAsStateWithLifecycle()
    val listItems by viewModel.listItems.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(ArchiveTab.ALL) }
    var showAdd by remember { mutableStateOf(false) } // TO_WATCH only now — notes get the full-screen editor
    var showNewList by remember { mutableStateOf(false) }
    var showNewShoppingItem by remember { mutableStateOf(false) }
    var openList by remember { mutableStateOf<ArchiveList?>(null) }
    var selectedFolder by remember { mutableStateOf<String?>(null) } // null = "Tutte"

    // Note editor: null+false = closed, null+true = new note, non-null = editing it.
    var showNoteEditor by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<ArchiveItem?>(null) }

    val notes = items.filter { it.kind == ArchiveKind.NOTE }
    val watch = items.filter { it.kind == ArchiveKind.TO_WATCH }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E)))),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Ink)
                }
                Text("Archivio", style = MaterialTheme.typography.headlineSmall, color = Cyan)
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArchiveTab.entries.forEach { entry ->
                    val active = tab == entry
                    Text(
                        text = entry.label,
                        color = if (active) Color(0xFF04121A) else Ink,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) Cyan else Color(0x330A1826))
                            .clickable { tab = entry }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }

            if (tab == ArchiveTab.ALL || tab == ArchiveTab.NOTES || tab == ArchiveTab.WATCH) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Cerca", color = Muted) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                    colors = jarvisFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            when (tab) {
                ArchiveTab.ALL -> AllTab(allRows)
                ArchiveTab.NOTES -> NotesTab(
                    notes = notes,
                    folders = folders,
                    selectedFolder = selectedFolder,
                    onSelectFolder = { selectedFolder = it },
                    onOpen = { editingNote = it; showNoteEditor = true },
                    onTogglePin = viewModel::togglePinned,
                    onDelete = viewModel::delete,
                )
                ArchiveTab.WATCH -> ItemsTab(
                    items = watch,
                    emptyText = "Nessun elemento nella lista da vedere.",
                    onClick = {},
                    onToggle = { viewModel.toggleWatched(it) },
                    onDelete = { viewModel.delete(it) },
                )
                ArchiveTab.LISTS -> ListsTab(
                    shoppingItems = shoppingItems,
                    customLists = customLists,
                    onAddShoppingItem = { showNewShoppingItem = true },
                    onToggleShoppingItem = { viewModel.toggleListItem("spesa", it) },
                    onDeleteShoppingItem = { viewModel.deleteListItem("spesa", it) },
                    onOpenList = { openList = it },
                )
                ArchiveTab.TODO -> RedirectTab(
                    icon = Icons.Filled.CheckCircle,
                    text = "Le attività (TODO) vivono nella schermata Attività, con liste, priorità e sotto-attività.",
                    buttonLabel = "Apri Attività",
                    onClick = onOpenTasks,
                )
                ArchiveTab.DOCUMENTS -> RedirectTab(
                    icon = Icons.Filled.Description,
                    text = "Documenti e foto personali (PDF, immagini con OCR) vivono nell'Archivio documenti.",
                    buttonLabel = "Apri Documenti",
                    onClick = onOpenDocuments,
                )
            }
        }

        // Floating "+", same shape/placement as Attività's — only where "add" means one obvious thing.
        val fabAction: (() -> Unit)? = when (tab) {
            ArchiveTab.NOTES -> { { editingNote = null; showNoteEditor = true } }
            ArchiveTab.WATCH -> { { showAdd = true } }
            ArchiveTab.LISTS -> { { showNewList = true } }
            else -> null
        }
        if (fabAction != null) {
            IconButton(
                onClick = fabAction,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Cyan),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Aggiungi", tint = Color(0xFF04121A))
            }
        }

        if (showNoteEditor) {
            NoteEditorScreen(
                note = editingNote,
                folders = folders,
                onDismiss = { showNoteEditor = false },
                onSave = { title, content, folder, pinned ->
                    val current = editingNote
                    if (current == null) {
                        viewModel.createNote(title, content, folder, pinned)
                    } else {
                        viewModel.updateNote(current, title, content, folder, pinned)
                    }
                    showNoteEditor = false
                },
                onDelete = editingNote?.let { note -> { viewModel.delete(note); showNoteEditor = false } },
            )
        }
    }

    if (showAdd) {
        AddWatchDialog(
            onDismiss = { showAdd = false },
            onCreate = { title, type, link -> viewModel.createWatchItem(title, type, link) },
        )
    }

    if (showNewList) {
        NameDialog(
            title = "Nuova lista",
            label = "Nome della lista",
            onDismiss = { showNewList = false },
            onConfirm = { name -> viewModel.createList(name) },
        )
    }

    if (showNewShoppingItem) {
        QuantityDialog(
            title = "Aggiungi alla spesa",
            onDismiss = { showNewShoppingItem = false },
            onConfirm = { title, qty -> viewModel.addToShopping(title, qty) },
        )
    }

    openList?.let { list ->
        ListDetailDialog(
            list = list,
            items = listItems.filter { it.listId == list.id },
            onDismiss = { openList = null },
            onAddItem = { title, qty -> viewModel.addListItem(list, title, qty) },
            onToggleItem = { viewModel.toggleListItem(list.name, it) },
            onDeleteItem = { viewModel.deleteListItem(list.name, it) },
            onDeleteList = { viewModel.deleteList(list); openList = null },
        )
    }
}

@Composable
private fun jarvisFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cyan.copy(alpha = 0.7f),
    unfocusedBorderColor = Muted.copy(alpha = 0.4f),
    focusedContainerColor = Color(0x330A1826),
    unfocusedContainerColor = Color(0x330A1826),
    cursorColor = Cyan,
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
)

@Composable
private fun AllTab(rows: List<ArchiveAllRow>) {
    if (rows.isEmpty()) {
        Text("L'archivio è vuoto.", color = Muted, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rows, key = { row -> when (row) { is ArchiveAllRow.Note -> "n:" + row.item.id; is ArchiveAllRow.ListEntry -> "l:" + row.item.id } }) { row ->
            when (row) {
                is ArchiveAllRow.Note -> DarkRow {
                    Text(row.item.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                    val subtitle = if (row.item.kind == ArchiveKind.NOTE) row.item.content else "Da vedere · ${row.item.watchType}"
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
                }
                is ArchiveAllRow.ListEntry -> DarkRow {
                    Text(row.item.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text("Lista: ${row.listName}", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun DarkRow(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(12.dp),
        content = content,
    )
}

// --- Appunti: folders, pinned, chronological grouping ----------------------

@Composable
private fun NotesTab(
    notes: List<ArchiveItem>,
    folders: List<String>,
    selectedFolder: String?,
    onSelectFolder: (String?) -> Unit,
    onOpen: (ArchiveItem) -> Unit,
    onTogglePin: (ArchiveItem) -> Unit,
    onDelete: (ArchiveItem) -> Unit,
) {
    val hasUnfiled = notes.any { it.folder == ARCHIVE_UNFILED_FOLDER }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FolderChip("Tutte", selectedFolder == null) { onSelectFolder(null) }
        if (hasUnfiled) FolderChip("Senza categoria", selectedFolder == ARCHIVE_UNFILED_FOLDER) { onSelectFolder(ARCHIVE_UNFILED_FOLDER) }
        folders.forEach { f -> FolderChip(f, selectedFolder == f) { onSelectFolder(f) } }
    }

    val filtered = if (selectedFolder == null) notes else notes.filter { it.folder == selectedFolder }
    if (filtered.isEmpty()) {
        Text("Nessuna nota. Tocca + per crearne una.", color = Muted, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val pinned = filtered.filter { it.pinned }
    val rest = filtered.filterNot { it.pinned }
    val groups = rest.groupBy { monthLabel(it.updatedAt) } // insertion order == reverse-chronological, notes already sorted

    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pinned.isNotEmpty()) {
            item(key = "pinned-header") { SectionHeader("In primo piano") }
            items(pinned, key = { "p:" + it.id }) { note ->
                NoteCard(note, onClick = { onOpen(note) }, onTogglePin = { onTogglePin(note) }, onDelete = { onDelete(note) })
            }
        }
        groups.forEach { (label, group) ->
            item(key = "h:$label") { SectionHeader(label) }
            items(group, key = { it.id }) { note ->
                NoteCard(note, onClick = { onOpen(note) }, onTogglePin = { onTogglePin(note) }, onDelete = { onDelete(note) })
            }
        }
    }
}

@Composable
private fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color(0xFF04121A) else Muted,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Cyan else Color(0x260A1826))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label.replaceFirstChar { it.uppercase() },
        color = Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** Italian month name, kept plain (no year) for the current year, "mese anno" otherwise. */
private fun monthLabel(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val month = date.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
    return if (date.year == LocalDate.now().year) month else "$month ${date.year}"
}

@Composable
private fun NoteCard(note: ArchiveItem, onClick: () -> Unit, onTogglePin: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(note.title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1)
            if (note.content.isNotBlank()) {
                Spacer(Modifier.size(2.dp))
                Text(note.content, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
            }
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = if (note.pinned) "Togli dai preferiti" else "Metti in primo piano",
                // One glyph, tint-switched — same idiom already used for the
                // star toggle in AgendaScreen (Icons.Filled.Star/StarBorder is
                // the exception because those have distinct icon names; PushPin's
                // filled/outlined variants share one name across two packages,
                // which Kotlin can't import unaliased in the same file).
                tint = if (note.pinned) Gold else Muted,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Elimina", tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The full-screen note editor that replaced the small edit dialog (§ richiesta
 * esplicita dell'utente, riferimento l'app Note del telefono): a big borderless
 * "Titolo" like [com.simone.jarvismobile.ui.agenda.AddTaskScreen]'s, a folder
 * chip that opens [FolderPickerDialog], and a content field that fills the rest
 * of the screen instead of a cramped multi-line box in a dialog.
 */
@Composable
private fun NoteEditorScreen(
    note: ArchiveItem?,
    folders: List<String>,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, folder: String, pinned: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var content by remember { mutableStateOf(note?.content ?: "") }
    var folder by remember { mutableStateOf(note?.folder ?: "") }
    var pinned by remember { mutableStateOf(note?.pinned ?: false) }
    var showFolderPicker by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E))))
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Annulla", tint = Ink)
                }
                Text(
                    if (note == null) "Nuova nota" else "Modifica nota",
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Elimina", tint = Muted)
                    }
                }
                IconButton(onClick = { pinned = !pinned }) {
                    Icon(
                        Icons.Filled.PushPin, // one glyph, tint-switched — see NoteCard's comment
                        contentDescription = "In primo piano",
                        tint = if (pinned) Gold else Muted,
                    )
                }
                TextButton(
                    onClick = { onSave(title, content, folder, pinned) },
                    enabled = title.isNotBlank(),
                ) { Text("Salva", color = if (title.isNotBlank()) Cyan else Muted) }
            }
            Spacer(Modifier.size(16.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Titolo", color = Muted, fontSize = 22.sp) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = Ink),
                colors = borderlessFieldColors(),
                singleLine = true,
            )
            FolderChip(folder.ifBlank { "Senza categoria" }, selected = false) { showFolderPicker = true }
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("Nota…", color = Muted) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                colors = borderlessFieldColors(),
            )
        }
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            folders = folders,
            current = folder,
            onDismiss = { showFolderPicker = false },
            onPick = { folder = it; showFolderPicker = false },
        )
    }
}

@Composable
private fun borderlessFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    cursorColor = Cyan,
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
)

@Composable
private fun FolderPickerDialog(folders: List<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var newFolder by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cartella") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Senza categoria",
                    modifier = Modifier.fillMaxWidth().clickable { onPick("") }.padding(vertical = 8.dp),
                    fontWeight = if (current.isBlank()) FontWeight.Bold else FontWeight.Normal,
                )
                folders.forEach { f ->
                    Text(
                        f,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 8.dp),
                        fontWeight = if (current == f) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        label = { Text("Nuova cartella") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onPick(newFolder.trim()) }, enabled = newFolder.isNotBlank()) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

// --- other tabs, unchanged logic, JARVIS-restyled ---------------------------

@Composable
private fun ItemsTab(
    items: List<ArchiveItem>,
    emptyText: String,
    onClick: (ArchiveItem) -> Unit,
    onToggle: (ArchiveItem) -> Unit,
    onDelete: (ArchiveItem) -> Unit,
) {
    if (items.isEmpty()) {
        Text(emptyText, color = Muted, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { item ->
            ArchiveRow(item = item, onClick = { onClick(item) }, onToggle = { onToggle(item) }, onDelete = { onDelete(item) })
        }
    }
}

@Composable
private fun ListsTab(
    shoppingItems: List<ArchiveListItem>,
    customLists: List<ArchiveList>,
    onAddShoppingItem: () -> Unit,
    onToggleShoppingItem: (ArchiveListItem) -> Unit,
    onDeleteShoppingItem: (ArchiveListItem) -> Unit,
    onOpenList: (ArchiveList) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Lista della spesa", style = MaterialTheme.typography.titleMedium, color = Ink) }
        if (shoppingItems.isEmpty()) {
            item { Text("Vuota.", style = MaterialTheme.typography.bodySmall, color = Muted) }
        } else {
            items(shoppingItems, key = { "spesa:" + it.id }) { it2 ->
                ListItemRow(item = it2, onToggle = { onToggleShoppingItem(it2) }, onDelete = { onDeleteShoppingItem(it2) })
            }
        }
        item {
            OutlinedButton(onClick = onAddShoppingItem, modifier = Modifier.fillMaxWidth()) { Text("Aggiungi alla spesa") }
        }
        item { Text("Le mie liste", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.padding(top = 12.dp)) }
        if (customLists.isEmpty()) {
            item { Text("Nessuna lista personalizzata. Tocca + per crearne una.", style = MaterialTheme.typography.bodySmall, color = Muted) }
        } else {
            items(customLists, key = { it.id }) { list ->
                DarkRow(content = { Text(list.name, style = MaterialTheme.typography.titleMedium, color = Ink) })
            }
        }
    }
}

@Composable
private fun ListItemRow(item: ArchiveListItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.status == ListItemStatus.DONE, onCheckedChange = { onToggle() })
        val label = if (item.quantity != null) "${item.title} (x${item.quantity})" else item.title
        Text(
            label,
            color = Ink,
            modifier = Modifier.weight(1f),
            textDecoration = if (item.status == ListItemStatus.DONE) TextDecoration.LineThrough else null,
        )
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Rimuovi", tint = Muted) }
    }
}

@Composable
private fun RedirectTab(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, buttonLabel: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = Muted)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
        OutlinedButton(onClick = onClick) { Text(buttonLabel) }
    }
}

@Composable
private fun ArchiveRow(item: ArchiveItem, onClick: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.kind == ArchiveKind.TO_WATCH) {
            Checkbox(checked = item.status == ArchiveStatus.DONE, onCheckedChange = { onToggle() })
        }
        val rowModifier = Modifier.weight(1f).padding(vertical = 8.dp)
        Column(modifier = rowModifier) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                textDecoration = if (item.status == ArchiveStatus.DONE) TextDecoration.LineThrough else null,
            )
            val subtitle = item.watchType
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Elimina", tint = Muted) }
    }
}

@Composable
private fun AddWatchDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuovo elemento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titolo") })
                OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text("Tipo (film, libro, serie…)") })
                OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text("Link (opzionale)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, type, link); onDismiss() }, enabled = title.isNotBlank()) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) }) },
        confirmButton = {
            TextButton(onClick = { onConfirm(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Crea") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun QuantityDialog(title: String, onDismiss: () -> Unit, onConfirm: (String, Int?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Cosa") })
                OutlinedTextField(value = qty, onValueChange = { qty = it }, label = { Text("Quantità (opzionale)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, qty.toIntOrNull()); onDismiss() }, enabled = name.isNotBlank()) { Text("Aggiungi") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun ListDetailDialog(
    list: ArchiveList,
    items: List<ArchiveListItem>,
    onDismiss: () -> Unit,
    onAddItem: (String, Int?) -> Unit,
    onToggleItem: (ArchiveListItem) -> Unit,
    onDeleteItem: (ArchiveListItem) -> Unit,
    onDeleteList: () -> Unit,
) {
    var newItem by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(list.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) {
                    Text("Lista vuota.", style = MaterialTheme.typography.bodySmall)
                } else {
                    items.forEach { it2 -> ListItemRow(item = it2, onToggle = { onToggleItem(it2) }, onDelete = { onDeleteItem(it2) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newItem,
                        onValueChange = { newItem = it },
                        label = { Text("Nuovo elemento") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAddItem(newItem, null); newItem = "" }, enabled = newItem.isNotBlank()) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        dismissButton = { TextButton(onClick = onDeleteList) { Text("Elimina lista") } },
    )
}
