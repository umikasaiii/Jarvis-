package com.simone.jarvismobile.ui.archive

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.memory.MemoryMarkup
import com.simone.jarvismobile.core.notes.NoteEntry
import com.simone.jarvismobile.core.notes.NoteSource
import com.simone.jarvismobile.core.notes.NotesFeed

/**
 * "Note" — one coherent notes screen (BETA 3) over the notes of both existing
 * stores (Archivio and Memoria), shown as grid or list, with search, folders,
 * pinned notes and a full-screen editor. See [NotesViewModel] for how edits
 * are routed back to the store that owns each note.
 */
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val hasUnfiled by viewModel.hasUnfiled.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selected by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val backgrounds by viewModel.customBackgrounds.collectAsStateWithLifecycle()

    var grid by rememberSaveable { mutableStateOf(true) }
    var editing by remember { mutableStateOf<NoteEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<NoteEntry?>(null) }
    var moveTarget by remember { mutableStateOf<NoteEntry?>(null) }
    var showFolderManager by remember { mutableStateOf(false) }

    val openNote: (NoteEntry) -> Unit = { editing = it; showEditor = true }

    LaunchedEffect(Unit) { viewModel.refreshMemory() }

    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBackground(it) }
    }

    BackHandler(enabled = !showEditor) { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E)))),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp)) {
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Ink)
                }
                Text("Note", style = MaterialTheme.typography.headlineSmall, color = Cyan, modifier = Modifier.weight(1f))
                TextButton(onClick = { grid = !grid }) { Text(if (grid) "Elenco" else "Griglia", color = Cyan) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Cerca nelle note", color = Muted) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                colors = jarvisNotesFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FolderChip("Tutte", selected == null) { viewModel.selectFolder(null) }
                if (hasUnfiled) FolderChip("Senza categoria", selected == NotesFeed.UNFILED) { viewModel.selectFolder(NotesFeed.UNFILED) }
                folders.forEach { f -> FolderChip(f, selected == f) { viewModel.selectFolder(f) } }
            }
            if (folders.isNotEmpty()) {
                Text(
                    "Gestisci cartelle", color = Cyan, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showFolderManager = true }.padding(vertical = 4.dp),
                )
            }
            if (message.isNotBlank()) {
                Text(message, color = Muted, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.clearMessage() }.padding(vertical = 4.dp))
            }

            if (notes.isEmpty()) {
                Text(
                    if (query.isBlank() && selected == null) "Nessuna nota. Tocca + per crearne una." else "Nessuna nota corrisponde.",
                    color = Muted, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val pinned = notes.filter { it.pinned }
                val rest = notes.filterNot { it.pinned }
                if (grid) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (pinned.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }, key = "ph") { SectionHeader("In primo piano") }
                            items(pinned, key = { "p:" + it.ref.key }) { n -> NoteTile(n, true, { openNote(n) }, viewModel::togglePin, { moveTarget = it }, { confirmDelete = it }) }
                            if (rest.isNotEmpty()) item(span = { GridItemSpan(2) }, key = "oh") { SectionHeader("Altre") }
                        }
                        items(rest, key = { it.ref.key }) { n -> NoteTile(n, true, { openNote(n) }, viewModel::togglePin, { moveTarget = it }, { confirmDelete = it }) }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pinned.isNotEmpty()) {
                            item(key = "ph") { SectionHeader("In primo piano") }
                            items(pinned, key = { "p:" + it.ref.key }) { n -> NoteTile(n, false, { openNote(n) }, viewModel::togglePin, { moveTarget = it }, { confirmDelete = it }) }
                            if (rest.isNotEmpty()) item(key = "oh") { SectionHeader("Altre") }
                        }
                        items(rest, key = { it.ref.key }) { n -> NoteTile(n, false, { openNote(n) }, viewModel::togglePin, { moveTarget = it }, { confirmDelete = it }) }
                    }
                }
            }
        }

        IconButton(
            onClick = { editing = null; showEditor = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(58.dp).clip(CircleShape).background(Cyan),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Nuova nota", tint = Color(0xFF04121A))
        }

        if (showEditor) {
            NoteEditorScreen(
                note = editing,
                folders = folders,
                customBackgrounds = backgrounds,
                backgroundStore = viewModel.backgroundStore,
                onImportBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                onDeleteBackground = viewModel::deleteBackground,
                onSave = viewModel::save,
                onDelete = viewModel::delete,
                onClose = { showEditor = false; editing = null },
            )
        }
    }

    confirmDelete?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Eliminare «${n.title}»?") },
            text = { Text("L'operazione non si può annullare.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(n.ref); confirmDelete = null }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annulla") } },
        )
    }
    moveTarget?.let { n ->
        FolderPickerDialog(
            folders = folders,
            current = n.folder,
            onDismiss = { moveTarget = null },
            onPick = { viewModel.move(n, it); moveTarget = null },
        )
    }
    if (showFolderManager) {
        FolderManagerDialog(
            folders = folders,
            onRename = viewModel::renameFolder,
            onDelete = viewModel::deleteFolder,
            onDismiss = { showFolderManager = false },
        )
    }
}

@Composable
private fun jarvisNotesFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cyan,
    unfocusedBorderColor = Color(0x33FFFFFF),
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    cursorColor = Cyan,
)

@Composable
private fun NoteTile(
    note: NoteEntry,
    tile: Boolean,
    onOpen: () -> Unit,
    onPin: (NoteEntry) -> Unit,
    onMove: (NoteEntry) -> Unit,
    onDelete: (NoteEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 8.dp, bottom = 12.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (note.pinned) Icon(Icons.Filled.PushPin, contentDescription = "In primo piano", tint = Gold, modifier = Modifier.size(16.dp))
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Azioni nota", tint = Muted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (note.canPin) {
                        DropdownMenuItem(
                            text = { Text(if (note.pinned) "Togli dal primo piano" else "Metti in primo piano") },
                            onClick = { menu = false; onPin(note) },
                        )
                    }
                    DropdownMenuItem(text = { Text("Sposta in cartella") }, onClick = { menu = false; onMove(note) })
                    DropdownMenuItem(text = { Text("Elimina") }, onClick = { menu = false; onDelete(note) })
                }
            }
        }
        val preview = if (note.sensitive) "Contenuto sensibile — apri per vedere" else MemoryMarkup.plainText(note.content)
        if (preview.isNotBlank()) {
            Text(preview, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = if (tile) 5 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 10.dp))
        }
        val tags = listOfNotNull(
            note.folder.takeIf { it.isNotBlank() },
            "Memoria".takeIf { note.ref.source == NoteSource.MEMORIA },
        )
        if (tags.isNotEmpty()) {
            Text(tags.joinToString(" · "), color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
