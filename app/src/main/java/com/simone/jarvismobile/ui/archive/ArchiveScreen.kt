package com.simone.jarvismobile.ui.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveStatus

/**
 * "Archivio" (spec §4): notes and a to-watch list, backed by [ArchiveRepository]
 * — the same store the AI's `create_note`/`search_memory`/… tools use, so
 * anything typed here is immediately something Pro Mode (or NORMAL mode's
 * `remember`) can find, and anything the AI creates shows up here.
 */
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(ArchiveKind.NOTE) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ArchiveItem?>(null) }

    val shown = items.filter { it.kind == tab }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archivio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            TabRow(selectedTabIndex = ArchiveKind.entries.indexOf(tab)) {
                Tab(selected = tab == ArchiveKind.NOTE, onClick = { tab = ArchiveKind.NOTE }, text = { Text("Note") })
                Tab(
                    selected = tab == ArchiveKind.TO_WATCH,
                    onClick = { tab = ArchiveKind.TO_WATCH },
                    text = { Text("Da vedere") },
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text("Cerca") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (shown.isEmpty()) {
                Text(
                    if (tab == ArchiveKind.NOTE) "Nessuna nota. Tocca + per crearne una." else "Nessun elemento nella lista.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { item ->
                        ArchiveRow(
                            item = item,
                            onClick = { if (item.kind == ArchiveKind.NOTE) editing = item },
                            onToggle = { viewModel.toggleWatched(item) },
                            onDelete = { viewModel.delete(item) },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDialog(
            kind = tab,
            onDismiss = { showAdd = false },
            onCreateNote = { title, content -> viewModel.createNote(title, content) },
            onCreateWatch = { title, type, link -> viewModel.createWatchItem(title, type, link) },
        )
    }

    editing?.let { item ->
        EditNoteDialog(
            item = item,
            onDismiss = { editing = null },
            onSave = { title, content -> viewModel.update(item, title, content) },
        )
    }
}

@Composable
private fun ArchiveRow(item: ArchiveItem, onClick: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (item.kind == ArchiveKind.TO_WATCH) {
                Checkbox(checked = item.status == ArchiveStatus.DONE, onCheckedChange = { onToggle() })
            }
            val rowModifier = if (item.kind == ArchiveKind.NOTE) {
                Modifier.weight(1f).padding(vertical = 8.dp).clickable(onClick = onClick)
            } else {
                Modifier.weight(1f).padding(vertical = 8.dp)
            }
            Column(modifier = rowModifier) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.status == ArchiveStatus.DONE) TextDecoration.LineThrough else null,
                )
                val subtitle = if (item.kind == ArchiveKind.NOTE) item.content else item.watchType
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Elimina")
            }
        }
    }
}

@Composable
private fun AddDialog(
    kind: ArchiveKind,
    onDismiss: () -> Unit,
    onCreateNote: (String, String) -> Unit,
    onCreateWatch: (String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == ArchiveKind.NOTE) "Nuova nota" else "Nuovo elemento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titolo") })
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(if (kind == ArchiveKind.NOTE) "Contenuto" else "Tipo (film, libro, serie…)") },
                )
                if (kind == ArchiveKind.TO_WATCH) {
                    OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text("Link (opzionale)") })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (kind == ArchiveKind.NOTE) onCreateNote(title, content) else onCreateWatch(title, content, link)
                    onDismiss()
                },
                enabled = title.isNotBlank(),
            ) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun EditNoteDialog(item: ArchiveItem, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    var content by remember { mutableStateOf(item.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica nota") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titolo") })
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("Contenuto") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, content); onDismiss() }, enabled = title.isNotBlank()) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
