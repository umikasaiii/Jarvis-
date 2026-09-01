package com.simone.jarvismobile.ui.archive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentStatus
import com.simone.jarvismobile.core.memory.MemoryCategories
import com.simone.jarvismobile.core.memory.MemoryLineSpacing
import com.simone.jarvismobile.core.memory.MemoryMarkup
import com.simone.jarvismobile.core.memory.MemoryNoteThemes
import com.simone.jarvismobile.core.memory.MemoryRecord
import com.simone.jarvismobile.document.folder
import com.simone.jarvismobile.memory.NoteBackgroundStore
import com.simone.jarvismobile.ui.memory.FormattingToolbar
import com.simone.jarvismobile.ui.memory.MarkupPreview
import com.simone.jarvismobile.ui.memory.MarkupVisualTransformation
import com.simone.jarvismobile.ui.memory.ThemeSelector
import com.simone.jarvismobile.ui.memory.customBackgroundRes
import com.simone.jarvismobile.ui.memory.insertDivider
import com.simone.jarvismobile.ui.memory.prefixLine
import com.simone.jarvismobile.ui.memory.rememberUserBackgroundBitmap
import com.simone.jarvismobile.ui.memory.setLineAlign
import com.simone.jarvismobile.ui.memory.spacingLineHeight
import com.simone.jarvismobile.ui.memory.themeBackgroundBrush
import com.simone.jarvismobile.ui.memory.toggleChecklistLine
import com.simone.jarvismobile.ui.memory.wrapSelection
import com.simone.jarvismobile.ui.memory.wrapSelectionWith
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

/**
 * The root "folders" Archivio browses — a real file-manager shape (§ richiesta
 * esplicita dell'utente, riferimento screenshot dell'app "File" del telefono),
 * not a tab bar. Each is a thin view over a store that already exists
 * elsewhere in the app; none of them is a second copy of that data.
 */
private enum class ArchiveFolder(val label: String, val icon: ImageVector) {
    DOCUMENTS("Documenti", Icons.Filled.Description),
    NOTES("Note", Icons.Filled.EditNote),
    // "Da vedere" non è più una cartella radice a sé (§ richiesta esplicita
    // dell'utente: "togli 'da vedere' e aggiungila a 'liste'") — i suoi
    // elementi (ArchiveKind.TO_WATCH, dato invariato) vivono ora come una
    // terza sezione dentro ListsTab, accanto a "Lista della spesa"/"Le mie
    // liste", non come schermata separata.
    LISTS("Liste", Icons.Filled.ListIcon),
    MEMORY("Memoria", Icons.Filled.Memory),
    TODO("TODO", Icons.Filled.CheckCircle),
}

/**
 * "Archivio" (spec §2/§4): a file-manager-shaped browser over everything
 * JARVIS already stores — imported files/photos ([DocumentRecord], including
 * ones attached straight from the chat), personal notes/lists/da-vedere
 * ([ArchiveItem]), and Memoria's records ([MemoryRecord], read-only here —
 * editing one still only happens in the Memoria screen). Root shows folders;
 * tapping one drills in with a breadcrumb back to root, mirroring the
 * reference file-manager app rather than a tab bar. TODO stays a pure
 * redirect into the already-complete Attività screen — no second task list.
 */
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenDocuments: () -> Unit,
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val documents by viewModel.filteredDocuments.collectAsStateWithLifecycle()
    val importingDocuments by viewModel.importingDocuments.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()
    val memoryRecords by viewModel.filteredMemory.collectAsStateWithLifecycle()
    val customLists by viewModel.customLists.collectAsStateWithLifecycle()
    val shoppingItems by viewModel.shoppingItems.collectAsStateWithLifecycle()
    val listItems by viewModel.listItems.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val documentFolders by viewModel.documentFolders.collectAsStateWithLifecycle()
    val customBackgrounds by viewModel.customBackgrounds.collectAsStateWithLifecycle()

    var openFolder by remember { mutableStateOf<ArchiveFolder?>(null) }
    var showAdd by remember { mutableStateOf(false) } // TO_WATCH only — notes get the full-screen editor
    var showNewList by remember { mutableStateOf(false) }
    var showNewShoppingItem by remember { mutableStateOf(false) }
    var openList by remember { mutableStateOf<ArchiveList?>(null) }
    var selectedNoteFolder by remember { mutableStateOf<String?>(null) } // null = "Tutte"
    var selectedDocFolder by remember { mutableStateOf<String?>(null) } // null = "Tutte"

    var showNoteEditor by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<ArchiveItem?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showNewDocFolder by remember { mutableStateOf(false) }

    val notes = items.filter { it.kind == ArchiveKind.NOTE }
    val watch = items.filter { it.kind == ArchiveKind.TO_WATCH }
    val context = LocalContext.current

    // "Importa dal telefono" (§ richiesta esplicita dell'utente) — the picker's
    // transient read grant is enough, same as the chat's own attach flow:
    // importFromPhone() reads the bytes immediately and copies them to
    // app-private storage, no persistable permission needed. Imports while a
    // folder is the active filter land straight in it (§ "non ho capito...
    // come creare le cartelle" — the same "+" now also offers "Nuova cartella").
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.importFromPhone(uris, selectedDocFolder.orEmpty()) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.importFromPhone(uris, selectedDocFolder.orEmpty()) }

    // Sfondo nota personalizzato, stesso picker/store di Memoria (§ richiesta
    // esplicita: "deve essere tutto personalizzabile: sfondo dietro").
    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackground(it) } }

    // Memoria is edited only in its own screen, so this view's copy can go
    // stale while the user is away — refresh whenever the folder is (re)opened.
    LaunchedEffect(openFolder) {
        if (openFolder == ArchiveFolder.MEMORY) viewModel.refreshMemory()
    }

    // System back (hardware button or an edge gesture, e.g. Honor's swipe from
    // the side) used to skip the on-screen arrow's logic entirely and fall
    // through to closing Archivio straight to Home — a real bug the user hit
    // (§ richiesta esplicita: "faccio per andare indietro... torna nella home
    // invece che nella cartella precedente"). Disabled at the root so a second
    // back there still closes Archivio normally.
    BackHandler(enabled = openFolder != null) {
        openFolder = null
        viewModel.setQuery("")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E)))),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp)) {
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (openFolder == null) onBack() else { openFolder = null; viewModel.setQuery("") }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Ink)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Archivio",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (openFolder == null) Cyan else Muted,
                        modifier = if (openFolder != null) Modifier.clickable { openFolder = null; viewModel.setQuery("") } else Modifier,
                    )
                    openFolder?.let {
                        Text(" ›  ", color = Muted, style = MaterialTheme.typography.headlineSmall)
                        Text(it.label, color = Cyan, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }

            if (openFolder != null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Cerca in «${openFolder!!.label}»", color = Muted) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                    colors = jarvisFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                )
            }

            when (openFolder) {
                null -> FolderRoot(
                    documentsCount = documents.size,
                    documentsUpdated = documents.maxOfOrNull { it.importedAt },
                    notesCount = notes.size,
                    notesUpdated = notes.maxOfOrNull { it.updatedAt },
                    listsCount = customLists.size,
                    memoryCount = memoryRecords.size,
                    memoryUpdated = memoryRecords.maxOfOrNull { it.updatedAt },
                    storageUsage = storageUsage,
                    onOpen = { openFolder = it },
                )
                ArchiveFolder.DOCUMENTS -> DocumentsFolder(
                    documents = documents,
                    importing = importingDocuments,
                    folders = documentFolders,
                    selectedFolder = selectedDocFolder,
                    onSelectFolder = { selectedDocFolder = it },
                    onOpen = { doc -> openDocument(context, viewModel, doc) },
                    onShare = { doc -> shareDocument(context, viewModel, doc) },
                    onDelete = { doc -> viewModel.removeDocument(doc) },
                    onRename = { doc, name -> viewModel.renameDocument(doc, name) },
                    onMove = { doc, folder -> viewModel.moveDocument(doc, folder) },
                    onRenameFolder = { old, new ->
                        viewModel.renameDocumentFolder(old, new)
                        if (selectedDocFolder == old) selectedDocFolder = new
                    },
                    onDeleteFolder = { path ->
                        viewModel.deleteDocumentFolder(path)
                        if (selectedDocFolder == path) selectedDocFolder = null
                    },
                )
                ArchiveFolder.NOTES -> NotesTab(
                    notes = notes,
                    folders = folders,
                    selectedFolder = selectedNoteFolder,
                    onSelectFolder = { selectedNoteFolder = it },
                    onOpen = { editingNote = it; showNoteEditor = true },
                    onTogglePin = viewModel::togglePinned,
                    onDelete = viewModel::delete,
                    onRenameFolder = { old, new ->
                        viewModel.renameNoteFolder(old, new)
                        if (selectedNoteFolder == old) selectedNoteFolder = new
                    },
                    onDeleteFolder = { path ->
                        viewModel.deleteNoteFolder(path)
                        if (selectedNoteFolder == path) selectedNoteFolder = null
                    },
                )
                ArchiveFolder.LISTS -> ListsTab(
                    shoppingItems = shoppingItems,
                    customLists = customLists,
                    watchItems = watch,
                    onAddShoppingItem = { showNewShoppingItem = true },
                    onToggleShoppingItem = { viewModel.toggleListItem("spesa", it) },
                    onDeleteShoppingItem = { viewModel.deleteListItem("spesa", it) },
                    onOpenList = { openList = it },
                    onAddWatchItem = { showAdd = true },
                    onToggleWatchItem = { viewModel.toggleWatched(it) },
                    onDeleteWatchItem = { viewModel.delete(it) },
                )
                ArchiveFolder.MEMORY -> MemoryFolder(records = memoryRecords)
                ArchiveFolder.TODO -> RedirectTab(
                    icon = Icons.Filled.CheckCircle,
                    text = "Le attività (TODO) vivono nella schermata Attività, con liste, priorità e sotto-attività.",
                    buttonLabel = "Apri Attività",
                    onClick = onOpenTasks,
                )
            }
        }

        // Floating "+", only where "add" means one obvious thing.
        val fabAction: (() -> Unit)? = when (openFolder) {
            ArchiveFolder.DOCUMENTS -> { { showImportMenu = true } }
            ArchiveFolder.NOTES -> { { editingNote = null; showNoteEditor = true } }
            ArchiveFolder.LISTS -> { { showNewList = true } }
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
                customBackgrounds = customBackgrounds,
                backgroundStore = viewModel.backgroundStore,
                onImportBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                onDeleteBackground = viewModel::deleteBackground,
                onDismiss = { showNoteEditor = false },
                onSave = { title, content, folder, pinned, theme, spacing ->
                    val current = editingNote
                    if (current == null) {
                        viewModel.createNote(title, content, folder, pinned, theme, spacing)
                    } else {
                        viewModel.updateNote(current, title, content, folder, pinned, theme, spacing)
                    }
                    showNoteEditor = false
                },
                onDelete = editingNote?.let { note -> { viewModel.delete(note); showNoteEditor = false } },
            )
        }
    }

    if (showImportMenu) {
        AlertDialog(
            onDismissRequest = { showImportMenu = false },
            title = { Text("Documenti") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Importa dal telefono, resta sul dispositivo come ogni file allegato in chat" +
                            (selectedDocFolder?.let { " — in «$it»." } ?: "."),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        "File",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showImportMenu = false; documentPicker.launch(ARCHIVE_DOCUMENT_MIME_TYPES) }
                            .padding(vertical = 10.dp),
                    )
                    Text(
                        "Foto",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showImportMenu = false; photoPicker.launch(arrayOf("image/*")) }
                            .padding(vertical = 10.dp),
                    )
                    // § "non ho capito in documenti come creare le cartelle" — con
                    // 0 file importati non esisteva alcun modo di crearne una: il
                    // solo percorso era "sposta in cartella" sul menu di un file
                    // già presente. Ora "Nuova cartella" è qui, accanto a File/Foto.
                    Text(
                        "Nuova cartella",
                        color = Cyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showImportMenu = false; showNewDocFolder = true }
                            .padding(vertical = 10.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showImportMenu = false }) { Text("Chiudi") } },
        )
    }

    if (showNewDocFolder) {
        NameDialog(
            title = "Nuova cartella",
            label = "Nome (usa “/” per una sottocartella)",
            onDismiss = { showNewDocFolder = false },
            onConfirm = { name -> selectedDocFolder = name; showNewDocFolder = false },
        )
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

// --- root folder list --------------------------------------------------

@Composable
private fun FolderRoot(
    documentsCount: Int,
    documentsUpdated: Long?,
    notesCount: Int,
    notesUpdated: Long?,
    listsCount: Int,
    memoryCount: Int,
    memoryUpdated: Long?,
    storageUsage: ArchiveViewModel.StorageUsage,
    onOpen: (ArchiveFolder) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { StorageUsageCard(storageUsage) }
        item {
            FolderRow(ArchiveFolder.DOCUMENTS, folderMeta(documentsUpdated, documentsCount, "file"), onOpen)
        }
        item {
            FolderRow(ArchiveFolder.NOTES, folderMeta(notesUpdated, notesCount, "note"), onOpen)
        }
        item {
            FolderRow(ArchiveFolder.LISTS, "$listsCount liste personalizzate", onOpen)
        }
        item {
            FolderRow(ArchiveFolder.MEMORY, folderMeta(memoryUpdated, memoryCount, "ricordi"), onOpen)
        }
        item {
            FolderRow(ArchiveFolder.TODO, "Apri Attività", onOpen)
        }
    }
}

/**
 * How much of the device's storage JARVIS's local archive is using (§
 * richiesta esplicita dell'utente: "se l'archivio locale di jarvis ha un
 * limite di memoria inserisci barra che indica quanto spazio è usato, e
 * scritto anche la qtà in MB o GB"). Honest about there being no JARVIS quota
 * — the fill is "used by JARVIS" against "used + still free on the device",
 * the real practical ceiling, not an invented one.
 */
@Composable
private fun StorageUsageCard(usage: ArchiveViewModel.StorageUsage) {
    val total = usage.usedBytes + usage.freeBytes
    val fraction = if (total > 0) (usage.usedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Spazio usato", color = Ink, style = MaterialTheme.typography.labelLarge)
            Text(
                "${formatBytes(usage.usedBytes)} · ${formatBytes(usage.freeBytes)} liberi",
                color = Muted,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.size(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            color = Cyan,
            trackColor = Muted.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
}

/** Abbreviated MB/GB, matching how the user asked for it ("scritte Mb e GB"). */
private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}

// A distinct accent per folder so the root reads like a set of coloured
// destinations rather than one flat grey list (§ richiesta esplicita
// dell'utente: "la home archivio strutturala in modo più carino").
private fun ArchiveFolder.accent(): Color = when (this) {
    ArchiveFolder.DOCUMENTS -> Color(0xFF3FD8F0)
    ArchiveFolder.NOTES -> Color(0xFFF1C40F)
    ArchiveFolder.LISTS -> Color(0xFF2ECC71)
    ArchiveFolder.MEMORY -> Color(0xFF7C5CFF)
    ArchiveFolder.TODO -> Color(0xFFE67E22)
}

@Composable
private fun FolderRow(folder: ArchiveFolder, meta: String, onOpen: (ArchiveFolder) -> Unit) {
    val accent = folder.accent()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .clickable { onOpen(folder) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(folder.icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.label, color = Ink, style = MaterialTheme.typography.titleMedium)
            Text(meta, color = Muted, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted)
    }
}

private fun folderMeta(lastUpdatedMs: Long?, count: Int, noun: String): String {
    val date = lastUpdatedMs?.takeIf { it > 0 }?.let { monthDayLabel(it) }
    return if (date != null) "aggiornato $date · $count $noun" else "$count $noun"
}

private fun monthDayLabel(epochMillis: Long): String {
    val d = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val month = d.month.getDisplayName(TextStyle.SHORT, Locale.ITALIAN)
    return "${d.dayOfMonth} $month"
}

// --- Documenti folder --------------------------------------------------

@Composable
private fun DocumentsFolder(
    documents: List<DocumentRecord>,
    importing: List<DocumentRecord>,
    folders: List<String>,
    selectedFolder: String?,
    onSelectFolder: (String?) -> Unit,
    onOpen: (DocumentRecord) -> Unit,
    onShare: (DocumentRecord) -> Unit,
    onDelete: (DocumentRecord) -> Unit,
    onRename: (DocumentRecord, String) -> Unit,
    onMove: (DocumentRecord, String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var sortByName by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<DocumentRecord?>(null) }
    var renameTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var moveTarget by remember { mutableStateOf<DocumentRecord?>(null) }
    var showFolderManager by remember { mutableStateOf(false) }

    if (documents.isEmpty() && importing.isEmpty()) {
        Text(
            "Ancora vuoto. I file allegati o importati in chat compaiono qui, oppure tocca «+» per importarli dal telefono.",
            color = Muted,
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    // Folders here are a tag on the document (§ richiesta esplicita
    // dell'utente: "creare cartelle... come in un archivio classico"), same
    // pattern as Appunti's — a folder only "exists" once at least one file
    // carries it, created on the fly from the move dialog below. A "/" in the
    // name nests it one level deep (§ "non posso creare sottocartelle").
    val hasUnfiled = documents.any { it.folder().isBlank() }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FolderChip("Tutti", selectedFolder == null) { onSelectFolder(null) }
        if (hasUnfiled) FolderChip("Senza cartella", selectedFolder == ARCHIVE_UNFILED_FOLDER) { onSelectFolder(ARCHIVE_UNFILED_FOLDER) }
        folders.forEach { f -> FolderChip(f, selectedFolder == f) { onSelectFolder(f) } }
    }
    if (folders.isNotEmpty()) {
        Text(
            "Gestisci cartelle",
            color = Cyan,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showFolderManager = true }
                .padding(horizontal = 2.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(4.dp))
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FolderChip("Data", !sortByName) { sortByName = false }
        FolderChip("Nome", sortByName) { sortByName = true }
    }

    val filteredImporting = when (selectedFolder) {
        null -> importing
        ARCHIVE_UNFILED_FOLDER -> importing.filter { it.folder().isBlank() }
        else -> importing.filter { it.folder() == selectedFolder }
    }
    val filtered = when (selectedFolder) {
        null -> documents
        ARCHIVE_UNFILED_FOLDER -> documents.filter { it.folder().isBlank() }
        else -> documents.filter { it.folder() == selectedFolder }
    }
    if (filtered.isEmpty() && filteredImporting.isEmpty()) {
        Text("Nessun file in questa cartella.", color = Muted, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val sorted = if (sortByName) filtered.sortedBy { it.displayName.lowercase() } else filtered
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (filteredImporting.isNotEmpty()) {
            items(filteredImporting, key = { "importing-${it.id}" }) { doc -> ImportingRow(doc) }
        }
        items(sorted, key = { it.id }) { doc ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .clickable { onOpen(doc) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(glyph(doc), fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.displayName, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    val size = if (doc.fileSize > 0) "${doc.fileSize / 1024} KB" else ""
                    val meta = if (doc.folder().isNotBlank()) "$size · ${doc.folder()}" else size
                    Text(meta, color = Muted, fontSize = 11.sp)
                }
                Box {
                    IconButton(onClick = { menuFor = doc }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Altre azioni", tint = Muted, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuFor?.id == doc.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Apri") }, onClick = { menuFor = null; onOpen(doc) })
                        DropdownMenuItem(text = { Text("Condividi") }, onClick = { menuFor = null; onShare(doc) })
                        DropdownMenuItem(text = { Text("Rinomina") }, onClick = { menuFor = null; renameTarget = doc })
                        DropdownMenuItem(text = { Text("Sposta in cartella") }, onClick = { menuFor = null; moveTarget = doc })
                        DropdownMenuItem(text = { Text("Elimina", color = MaterialTheme.colorScheme.error) }, onClick = { menuFor = null; onDelete(doc) })
                    }
                }
            }
        }
    }

    renameTarget?.let { doc ->
        NameDialog(
            title = "Rinomina",
            label = "Nome",
            initial = doc.displayName,
            onDismiss = { renameTarget = null },
            onConfirm = { name -> onRename(doc, name) },
        )
    }
    moveTarget?.let { doc ->
        FolderPickerDialog(
            folders = folders,
            current = doc.folder(),
            onDismiss = { moveTarget = null },
            onPick = { picked -> onMove(doc, picked); moveTarget = null },
        )
    }
    if (showFolderManager) {
        FolderManagerDialog(
            folders = folders,
            onRename = onRenameFolder,
            onDelete = onDeleteFolder,
            onDismiss = { showFolderManager = false },
        )
    }
}

/** Same allowlist the chat's own "+" already offers for documents (§ import parity). */
private val ARCHIVE_DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain",
    "text/markdown",
    "application/json",
    "text/csv",
    "text/*",
)

private fun glyph(doc: DocumentRecord): String =
    when (doc.fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "📕"
        "doc", "docx" -> "📘"
        "csv", "tsv", "xlsx" -> "📊"
        "json" -> "🗂️"
        "md", "markdown" -> "📝"
        "epub" -> "📚"
        "png", "jpg", "jpeg", "webp" -> "🖼️"
        else -> "📄"
    }

/**
 * One row for a document still importing (§ richiesta esplicita dell'utente:
 * "quando carica fai vedere barra di avanzamento"). No separate progress
 * plumbing needed — [DocumentImportManager] already ticks
 * [DocumentRecord.status] through the real pipeline stages, so the bar is
 * just that status mapped to a fraction.
 */
@Composable
private fun ImportingRow(doc: DocumentRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph(doc), fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.displayName, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(4.dp))
            LinearProgressIndicator(
                progress = { doc.status.importProgress() },
                color = Cyan,
                trackColor = Muted.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.size(4.dp))
            Text(doc.status.importLabel(), color = Muted, fontSize = 11.sp)
        }
    }
}

private fun DocumentStatus.importProgress(): Float = when (this) {
    DocumentStatus.SELECTED -> 0.08f
    DocumentStatus.COPYING -> 0.32f
    DocumentStatus.PARSING -> 0.58f
    DocumentStatus.INDEXING -> 0.85f
    DocumentStatus.READY -> 1f
    DocumentStatus.FAILED -> 0f
}

private fun DocumentStatus.importLabel(): String = when (this) {
    DocumentStatus.SELECTED -> "In coda…"
    DocumentStatus.COPYING -> "Copia in corso…"
    DocumentStatus.PARSING -> "Estrazione testo…"
    DocumentStatus.INDEXING -> "Indicizzazione…"
    DocumentStatus.READY -> "Pronto"
    DocumentStatus.FAILED -> "Non riuscito"
}

/** Opens the file in whatever app the device offers for its type — a viewer, a photo app, etc. */
private fun openDocument(context: Context, viewModel: ArchiveViewModel, doc: DocumentRecord) {
    val uri = viewModel.shareUri(doc) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, doc.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { if (it is ActivityNotFoundException) shareDocument(context, viewModel, doc) }
}

/** "Condividi" — the standard Android share sheet, which also covers "save a copy" via Files/Drive-style targets. */
private fun shareDocument(context: Context, viewModel: ArchiveViewModel, doc: DocumentRecord) {
    val uri = viewModel.shareUri(doc) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = doc.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Condividi ${doc.displayName}")) }
}

// --- Memoria folder (read-only) -----------------------------------------

/**
 * Memoria's records grouped by category, read-only — editing stays in the
 * Memoria screen itself (no third editor for the same store). Reuses
 * [MemoryCategories] so the grouping matches exactly what Memoria itself
 * shows, never a second classification scheme.
 */
@Composable
private fun MemoryFolder(records: List<MemoryRecord>) {
    if (records.isEmpty()) {
        Text(
            "Ancora vuoto. I ricordi salvati in Memoria compaiono qui, divisi per categoria.",
            color = Muted,
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val byCategory = records.groupBy { it.category.ifBlank { "Senza categoria" } }
    val ordered = MemoryCategories.CANONICAL.filter { byCategory.containsKey(it) } +
        (byCategory.keys - MemoryCategories.CANONICAL.toSet())
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ordered.forEach { category ->
            val list = byCategory[category].orEmpty()
            item(key = "h:$category") { SectionHeader("$category (${list.size})") }
            items(list, key = { it.id }) { record ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(record.text, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
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
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var showFolderManager by remember { mutableStateOf(false) }
    val hasUnfiled = notes.any { it.folder == ARCHIVE_UNFILED_FOLDER }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FolderChip("Tutte", selectedFolder == null) { onSelectFolder(null) }
        if (hasUnfiled) FolderChip("Senza categoria", selectedFolder == ARCHIVE_UNFILED_FOLDER) { onSelectFolder(ARCHIVE_UNFILED_FOLDER) }
        folders.forEach { f -> FolderChip(f, selectedFolder == f) { onSelectFolder(f) } }
    }
    if (folders.isNotEmpty()) {
        Text(
            "Gestisci cartelle",
            color = Cyan,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showFolderManager = true }
                .padding(horizontal = 2.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(4.dp))
    }

    val filtered = if (selectedFolder == null) notes else notes.filter { it.folder == selectedFolder }
    if (filtered.isEmpty()) {
        Text("Nessuna nota. Tocca + per crearne una.", color = Muted, modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
    } else {
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

    if (showFolderManager) {
        FolderManagerDialog(
            folders = folders,
            onRename = onRenameFolder,
            onDelete = onDeleteFolder,
            onDismiss = { showFolderManager = false },
        )
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
                // Testo semplice, non la sintassi grezza (§ ora che il
                // contenuto può portare markup **grassetto**/[color=...]/ecc.,
                // stesso fix già fatto per NoteTile di Memoria).
                Text(MemoryMarkup.plainText(note.content), style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
            }
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = if (note.pinned) "Togli dai preferiti" else "Metti in primo piano",
                // One glyph, tint-switched — PushPin's filled/outlined variants
                // share one name across two Compose packages, which Kotlin
                // cannot import unaliased in the same file.
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
 * The full-screen note editor: a big borderless "Titolo" like
 * [com.simone.jarvismobile.ui.agenda.AddTaskScreen]'s, a folder chip that
 * opens [FolderPickerDialog], and a content field that fills the rest of the
 * screen instead of a cramped multi-line box in a dialog.
 *
 * Formatting/background are the exact same machinery Memoria's editor already
 * built (§ richiesta esplicita dell'utente: "deve essere tutto personalizzabile:
 * sfondo dietro, colore, carattere, ecc.") — [FormattingToolbar]/
 * [MarkupVisualTransformation]/[ThemeSelector]/[MemoryNoteThemes]/
 * [MemoryLineSpacing], reused verbatim from `ui.memory.MemoryScreen`, not a
 * second rich-text engine: an Archivio note's [ArchiveItem.content] stores
 * the identical inline markup Memoria's `MemoryRecord.text` does.
 */
@Composable
private fun NoteEditorScreen(
    note: ArchiveItem?,
    folders: List<String>,
    customBackgrounds: List<String>,
    backgroundStore: NoteBackgroundStore,
    onImportBackground: () -> Unit,
    onDeleteBackground: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, folder: String, pinned: Boolean, theme: String, spacing: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var field by remember { mutableStateOf(TextFieldValue(note?.content ?: "")) }
    var folder by remember { mutableStateOf(note?.folder ?: "") }
    var pinned by remember { mutableStateOf(note?.pinned ?: false) }
    var theme by remember { mutableStateOf(MemoryNoteThemes.sanitize(note?.theme ?: "")) }
    var spacing by remember { mutableStateOf(MemoryLineSpacing.sanitize(note?.spacing ?: "")) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }

    fun save() = onSave(title, field.text, folder, pinned, theme, spacing)

    Box(Modifier.fillMaxSize().imePadding()) {
        val backgroundRes = customBackgroundRes(theme)
        val userBitmap = if (MemoryNoteThemes.isUserImage(theme)) rememberUserBackgroundBitmap(backgroundStore, theme) else null
        if (backgroundRes != null || userBitmap != null) {
            if (backgroundRes != null) {
                Image(
                    painter = painterResource(backgroundRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    bitmap = userBitmap!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // A dark scrim over the artwork — Ink is a light tone made for a
            // dark background, and these images are mostly light "paper".
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xCC050C16), Color(0xB3081420), Color(0xCC03080E)))),
            )
        } else {
            Box(Modifier.fillMaxSize().background(themeBackgroundBrush(theme)))
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp)) {
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
                TextButton(onClick = ::save, enabled = title.isNotBlank()) {
                    Text("Salva", color = if (title.isNotBlank()) Cyan else Muted)
                }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FolderChip(folder.ifBlank { "Senza categoria" }, selected = false) { showFolderPicker = true }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showPreview = !showPreview }) {
                    Text(if (showPreview) "Modifica" else "Anteprima", color = Cyan)
                }
            }
            ThemeSelector(
                current = theme,
                onSelect = { theme = it },
                customUserBackgrounds = customBackgrounds,
                backgroundStore = backgroundStore,
                onImport = onImportBackground,
                onDelete = { id -> onDeleteBackground(id); if (theme == id) theme = MemoryNoteThemes.DEFAULT },
            )
            Spacer(Modifier.size(8.dp))
            if (showPreview) {
                MarkupPreview(
                    raw = field.text,
                    accent = Cyan,
                    spacing = spacing,
                    onToggleLine = { index -> field = field.copy(text = toggleChecklistLine(field.text, index)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = { Text("Nota…", color = Muted) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink, lineHeight = spacingLineHeight(spacing)),
                    visualTransformation = MarkupVisualTransformation,
                    colors = borderlessFieldColors(),
                )
                FormattingToolbar(
                    onTitle = { field = prefixLine(field, "# ") },
                    onSubtitle = { field = prefixLine(field, "## ") },
                    onBold = { field = wrapSelection(field, "**") },
                    onItalic = { field = wrapSelection(field, "*") },
                    onUnderline = { field = wrapSelectionWith(field, "<u>", "</u>") },
                    onHighlight = { field = wrapSelection(field, "==") },
                    onTextColor = { hex -> field = wrapSelectionWith(field, "[color=$hex]", "[/color]") },
                    onHighlightColor = { hex -> field = wrapSelectionWith(field, "[hl=$hex]", "[/hl]") },
                    onFontSize = { tag -> field = wrapSelectionWith(field, "[size=$tag]", "[/size]") },
                    onBullet = { field = prefixLine(field, "- ") },
                    onNumbered = { field = prefixLine(field, "1. ") },
                    onChecklist = { field = prefixLine(field, "- [ ] ") },
                    onDivider = { field = insertDivider(field) },
                    onAlignStart = { field = setLineAlign(field, "") },
                    onAlignCenter = { field = setLineAlign(field, "[center]") },
                    onAlignEnd = { field = setLineAlign(field, "[right]") },
                    spacing = spacing,
                    onSpacing = { spacing = it },
                )
            }
            Spacer(Modifier.size(16.dp))
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
                        label = { Text("Nuova (usa “/” per una sottocartella)") },
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

/**
 * Rename/delete every folder in one list (§ richiesta esplicita dell'utente:
 * "le cartelle non sono modificabili") — shared between Appunti and
 * Documenti, since both are just a flat `List<String>` of folder paths with
 * the same "Parent/Child" convention. Deleting un-files the folder's items
 * rather than deleting them — same non-destructive rule as everywhere else
 * a folder can be cleared in this app.
 */
@Composable
private fun FolderManagerDialog(
    folders: List<String>,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gestisci cartelle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (folders.isEmpty()) {
                    Text("Nessuna cartella.", style = MaterialTheme.typography.bodySmall)
                }
                folders.forEach { f ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(f, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { renameTarget = f }) { Text("Rinomina") }
                        TextButton(onClick = { deleteTarget = f }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
    renameTarget?.let { path ->
        NameDialog(
            title = "Rinomina cartella",
            label = "Nome",
            initial = path.substringAfterLast('/'),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                val parent = path.substringBeforeLast('/', "")
                onRename(path, if (parent.isBlank()) newName else "$parent/$newName")
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { path ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminare «$path»?") },
            text = { Text("Gli elementi al suo interno non vengono cancellati: tornano semplicemente senza cartella.") },
            confirmButton = {
                TextButton(onClick = { onDelete(path); deleteTarget = null }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annulla") } },
        )
    }
}

// --- other folders, unchanged logic, JARVIS-restyled ------------------------

@Composable
private fun ListsTab(
    shoppingItems: List<ArchiveListItem>,
    customLists: List<ArchiveList>,
    watchItems: List<ArchiveItem>,
    onAddShoppingItem: () -> Unit,
    onToggleShoppingItem: (ArchiveListItem) -> Unit,
    onDeleteShoppingItem: (ArchiveListItem) -> Unit,
    onOpenList: (ArchiveList) -> Unit,
    onAddWatchItem: () -> Unit,
    onToggleWatchItem: (ArchiveItem) -> Unit,
    onDeleteWatchItem: (ArchiveItem) -> Unit,
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
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg).clickable { onOpenList(list) }.padding(12.dp),
                ) {
                    Text(list.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                }
            }
        }
        // "Da vedere" era una cartella radice a sé (film/libri/serie da
        // recuperare, ArchiveKind.TO_WATCH — dato invariato) — spostata qui
        // come terza sezione (§ richiesta esplicita dell'utente: "togli 'da
        // vedere' e aggiungila a 'liste'"), stesso pattern "titolo + righe +
        // pulsante aggiungi" già usato sopra per la spesa.
        item { Text("Da vedere", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.padding(top = 12.dp)) }
        if (watchItems.isEmpty()) {
            item { Text("Nessun elemento nella lista da vedere.", style = MaterialTheme.typography.bodySmall, color = Muted) }
        } else {
            items(watchItems, key = { "watch:" + it.id }) { w ->
                ArchiveRow(item = w, onClick = {}, onToggle = { onToggleWatchItem(w) }, onDelete = { onDeleteWatchItem(w) })
            }
        }
        item {
            OutlinedButton(onClick = onAddWatchItem, modifier = Modifier.fillMaxWidth()) { Text("Aggiungi da vedere") }
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
private fun RedirectTab(icon: ImageVector, text: String, buttonLabel: String, onClick: () -> Unit) {
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
private fun NameDialog(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
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
