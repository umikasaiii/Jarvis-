package com.simone.jarvismobile.ui.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveList
import com.simone.jarvismobile.core.archive.ArchiveListItem
import com.simone.jarvismobile.core.archive.ArchiveStatus
import com.simone.jarvismobile.core.archive.ListItemStatus

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
 * Everything else here works as a normal notes/lists app with no AI involved,
 * backed by the same [ArchiveRepository]/[ArchiveListRepository] the AI tools use.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    var tab by remember { mutableStateOf(ArchiveTab.ALL) }
    var kindForAdd by remember { mutableStateOf(ArchiveKind.NOTE) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ArchiveItem?>(null) }
    var showNewList by remember { mutableStateOf(false) }
    var showNewShoppingItem by remember { mutableStateOf(false) }
    var openList by remember { mutableStateOf<ArchiveList?>(null) }

    val notes = items.filter { it.kind == ArchiveKind.NOTE }
    val watch = items.filter { it.kind == ArchiveKind.TO_WATCH }

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
            when (tab) {
                ArchiveTab.NOTES -> FloatingActionButton(onClick = { kindForAdd = ArchiveKind.NOTE; showAdd = true }) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
                ArchiveTab.WATCH -> FloatingActionButton(onClick = { kindForAdd = ArchiveKind.TO_WATCH; showAdd = true }) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
                ArchiveTab.LISTS -> FloatingActionButton(onClick = { showNewList = true }) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
                else -> Unit
            }
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            ScrollableTabRow(selectedTabIndex = tab.ordinal) {
                ArchiveTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.label) })
                }
            }

            if (tab == ArchiveTab.ALL || tab == ArchiveTab.NOTES || tab == ArchiveTab.WATCH) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Cerca") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            when (tab) {
                ArchiveTab.ALL -> AllTab(allRows)
                ArchiveTab.NOTES -> ItemsTab(
                    items = notes,
                    emptyText = "Nessuna nota. Tocca + per crearne una.",
                    onClick = { editing = it },
                    onToggle = {},
                    onDelete = { viewModel.delete(it) },
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
    }

    if (showAdd) {
        AddDialog(
            kind = kindForAdd,
            onDismiss = { showAdd = false },
            onCreateNote = { title, content -> viewModel.createNote(title, content) },
            onCreateWatch = { title, type, link -> viewModel.createWatchItem(title, type, link) },
        )
    }

    editing?.let { item ->
        EditNoteDialog(item = item, onDismiss = { editing = null }, onSave = { title, content -> viewModel.update(item, title, content) })
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
private fun AllTab(rows: List<ArchiveAllRow>) {
    if (rows.isEmpty()) {
        Text("L'archivio è vuoto.", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rows, key = { row -> when (row) { is ArchiveAllRow.Note -> "n:" + row.item.id; is ArchiveAllRow.ListEntry -> "l:" + row.item.id } }) { row ->
            when (row) {
                is ArchiveAllRow.Note -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.item.title, style = MaterialTheme.typography.titleMedium)
                        val subtitle = if (row.item.kind == ArchiveKind.NOTE) row.item.content else "Da vedere · ${row.item.watchType}"
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
                is ArchiveAllRow.ListEntry -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.item.title, style = MaterialTheme.typography.titleMedium)
                        Text("Lista: ${row.listName}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemsTab(
    items: List<ArchiveItem>,
    emptyText: String,
    onClick: (ArchiveItem) -> Unit,
    onToggle: (ArchiveItem) -> Unit,
    onDelete: (ArchiveItem) -> Unit,
) {
    if (items.isEmpty()) {
        Text(emptyText, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Lista della spesa", style = MaterialTheme.typography.titleMedium)
        }
        if (shoppingItems.isEmpty()) {
            item { Text("Vuota.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(shoppingItems, key = { "spesa:" + it.id }) { it2 ->
                ListItemRow(item = it2, onToggle = { onToggleShoppingItem(it2) }, onDelete = { onDeleteShoppingItem(it2) })
            }
        }
        item {
            OutlinedButton(onClick = onAddShoppingItem, modifier = Modifier.fillMaxWidth()) { Text("Aggiungi alla spesa") }
        }
        item { Text("Le mie liste", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        if (customLists.isEmpty()) {
            item { Text("Nessuna lista personalizzata. Tocca + per crearne una.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(customLists, key = { it.id }) { list ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenList(list) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text(list.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
                }
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
            modifier = Modifier.weight(1f),
            textDecoration = if (item.status == ListItemStatus.DONE) TextDecoration.LineThrough else null,
        )
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Rimuovi") }
    }
}

@Composable
private fun RedirectTab(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, buttonLabel: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onClick) { Text(buttonLabel) }
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
            verticalAlignment = Alignment.CenterVertically,
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
