package com.simone.jarvismobile.ui.memory

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simone.jarvismobile.R
import com.simone.jarvismobile.core.memory.InlineStyle
import com.simone.jarvismobile.core.memory.MarkupAlign
import com.simone.jarvismobile.core.memory.MemoryCategories
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryLineSpacing
import com.simone.jarvismobile.core.memory.MemoryMarkup
import com.simone.jarvismobile.core.memory.MemoryNoteThemes
import com.simone.jarvismobile.core.memory.MemoryRecord
import com.simone.jarvismobile.core.memory.ShortTermMemorySnapshot
import com.simone.jarvismobile.core.memory.SizeStep
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.NoteBackgroundStore
import com.simone.jarvismobile.ui.components.ThemedIcon
import com.simone.jarvismobile.ui.theme.LocalJarvisPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val shortTerm by viewModel.shortTerm.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) } // null = "Tutte le note"
    var query by remember { mutableStateOf("") }

    // Sfondi personalizzati importati dall'utente (§ richiesta esplicita
    // dell'utente, dopo il chiarimento su perché immagini con licenza non
    // possono essere bundlate nell'app: "Perfetto, mi piace" sull'importarle
    // dalla propria galleria invece) — vivono solo in storage app-privato,
    // mai in questo repository.
    val customBackgrounds by viewModel.customBackgrounds.collectAsStateWithLifecycle()
    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackground(it) } }

    // Every folder Memoria knows about — the fixed macro-categories plus any
    // free-form one a record already carries — so the drawer/editor picker
    // (§ richiesta esplicita dell'utente: "menu laterale vero e proprio", non
    // solo le categorie AI fisse) always offers exactly what's really in use.
    val allCategories = remember(records) {
        (MemoryCategories.CANONICAL + records.mapNotNull { it.category.takeIf(String::isNotBlank) }).distinct()
    }

    // Full-screen editor, like a phone notes app rather than a cramped dialog
    // (§ richiesta esplicita dell'utente, riferimento Note del telefono) — an
    // early return keeps it out of the main list's Box entirely instead of
    // stacking it as an overlay.
    if (showAdd) {
        MemoryNoteEditorScreen(
            title = "Nuovo ricordo",
            initialText = "",
            initialKind = MemoryKind.PERMANENT,
            initialCategory = selectedCategory.orEmpty(), // a note started from inside a drawer folder lands there
            initialTheme = "",
            initialSpacing = "",
            allowTemporary = true,
            showDelete = false,
            enabled = !busy,
            allCategories = allCategories,
            customBackgrounds = customBackgrounds,
            backgroundStore = viewModel.backgroundStore,
            onImportBackground = { backgroundPicker.launch(arrayOf("image/*")) },
            onDeleteBackground = viewModel::deleteBackground,
            onDismiss = { showAdd = false },
            onSave = { text, kind, category, theme, spacing ->
                viewModel.add(text, kind, category, theme, spacing)
                showAdd = false
            },
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
            initialTheme = rec.theme,
            initialSpacing = rec.spacing,
            allowTemporary = false,
            showDelete = true,
            enabled = !busy,
            allCategories = allCategories,
            topics = rec.topics,
            people = rec.people,
            dates = rec.dates,
            customBackgrounds = customBackgrounds,
            backgroundStore = viewModel.backgroundStore,
            onImportBackground = { backgroundPicker.launch(arrayOf("image/*")) },
            onDeleteBackground = viewModel::deleteBackground,
            onDismiss = { editing = null },
            onSave = { text, kind, category, theme, spacing ->
                viewModel.update(rec.id, text, kind, theme, spacing)
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
                .windowInsetsPadding(WindowInsets.statusBars)
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
                IconButton(onClick = { showDrawer = true }) {
                    // Rouge gets the user's own reference art (§ richiesta
                    // esplicita: "Usa questa per icona del menu") instead of
                    // the plain Material hamburger — same ThemedIcon pattern
                    // already used for every other Rouge-themed icon in the
                    // app; explicit size, since the Image branch has no
                    // built-in 24dp default the way Icon() does.
                    ThemedIcon(
                        Icons.Filled.Menu,
                        R.drawable.rouge_ic_menu,
                        tint = INK,
                        contentDescription = "Cartelle",
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        selectedCategory ?: "Memoria",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
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

            val filtered = if (query.isBlank()) {
                records
            } else {
                records.filter { it.text.contains(query, ignoreCase = true) }
            }
            if (query.isNotBlank() && filtered.isEmpty()) {
                Text("Nessun ricordo trovato per «$query».", style = MaterialTheme.typography.bodySmall, color = MUTED)
            }
            val currentFolder = selectedCategory
            if (currentFolder != null) {
                // Inside one folder from the drawer: a flat list, no repeated
                // section header (the screen title above already names it).
                val inFolder = filtered.filter { it.category.ifBlank { UNCATEGORIZED } == currentFolder }
                TextButton(onClick = { selectedCategory = null }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("‹ Tutte le note", color = accent)
                }
                if (inFolder.isEmpty()) {
                    Text("Ancora niente qui.", style = MaterialTheme.typography.bodySmall, color = MUTED)
                } else {
                    NoteGrid(inFolder, enabled = !busy, onOpen = { editing = it })
                }
            } else {
                // Notes grouped by category. The four running lists are always
                // shown, then the AI categories alphabetical, then the
                // not-yet-sorted bucket. Each section is collapsible — its
                // open/closed state is kept per name.
                val collapsed = remember { mutableStateMapOf<String, Boolean>() }
                val byCategory = filtered.groupBy { it.category.ifBlank { UNCATEGORIZED } }
                val listCats = MemoryCategories.LISTS
                val otherCats = (byCategory.keys - listCats.toSet() - UNCATEGORIZED).sorted()
                val ordered = listCats + otherCats +
                    listOfNotNull(UNCATEGORIZED.takeIf(byCategory::containsKey))
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

        if (showDrawer) {
            MemoryDrawer(
                records = records,
                categories = allCategories,
                selected = selectedCategory,
                onSelectAll = { selectedCategory = null; showDrawer = false },
                onSelectCategory = { selectedCategory = it; showDrawer = false },
                onRenameCategory = { old, new ->
                    viewModel.renameCategory(old, new)
                    if (selectedCategory == old) selectedCategory = new
                },
                onDeleteCategory = { path ->
                    viewModel.deleteCategory(path)
                    if (selectedCategory == path) selectedCategory = null
                },
                onDismiss = { showDrawer = false },
            )
        }
    }

    if (showOptions) {
        MemorySettingsDialog(
            shortTerm = shortTerm,
            busy = busy,
            onDismiss = { showOptions = false },
            onClearTemporary = viewModel::clearTemporary,
        )
    }
}

/**
 * "⋮" on the main screen: the short-term recap — a settings-shaped thing, not a
 * note — used to sit inline at the bottom of the notes feed. A classic phone
 * Notes app keeps its screen to just notes and tucks everything else away; this
 * dialog is that tuck-away. The Obsidian vault section that used to live here
 * was removed (§ richiesta esplicita dell'utente: "Togli sincronizzazione con
 * obsidian e rimaniamo solo su archivio locale") — Memoria no longer touches
 * any vault; the vault connection Documenti/Agenda/Automazioni still optionally
 * use moved to Impostazioni › Memoria & Conoscenza › Documenti.
 */
@Composable
private fun MemorySettingsDialog(
    shortTerm: ShortTermMemorySnapshot,
    busy: Boolean,
    onDismiss: () -> Unit,
    onClearTemporary: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Altre opzioni") },
        text = {
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

/**
 * A folder tree with exactly one level of nesting — "Parent/Child", matching
 * the depth the Samsung Notes reference itself showed ("Cartella personale" >
 * "Data"/"N8N"/…), not arbitrary depth. [MemoryRecord.category] stays a plain
 * string (no schema change): a "/" in it is simply read as a path separator.
 */
private data class FolderNode(val name: String, val path: String, val children: List<FolderNode>)

private fun buildFolderTree(paths: List<String>): List<FolderNode> {
    val top = paths.map { it.substringBefore('/') }.distinct().sorted()
    return top.map { t ->
        val children = paths.filter { it.startsWith("$t/") }
            .map { it.substringAfter('/') }
            .distinct()
            .sorted()
            .map { FolderNode(name = it, path = "$t/$it", children = emptyList()) }
        FolderNode(name = t, path = t, children = children)
    }
}

/**
 * The folders side-drawer (§ richiesta esplicita dell'utente: "menu laterale
 * vero e proprio... come nelle foto delle Note del telefono") — "Tutte le
 * note" with the total count, every known folder with its own count and a
 * "⋮" menu (rinomina/elimina/sottocartella — § "le cartelle non sono
 * modificabili... non posso creare sottocartelle"), and a "+ Nuova cartella"
 * entry at the bottom. A folder here is just [MemoryRecord.category] — the
 * same field the AI classifier already writes — so a hand-typed name is a
 * real, first-class category, not a second, app-only grouping concept.
 * Creating one selects it as the current filter (0 notes yet); the next "+"
 * note defaults into it, exactly the "make a folder, then fill it" flow a
 * phone Notes app offers, without a separate "known empty folders" store.
 */
@Composable
private fun MemoryDrawer(
    records: List<MemoryRecord>,
    categories: List<String>,
    selected: String?,
    onSelectAll: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tree = remember(categories) { buildFolderTree(categories) }
    val expandedOverride = remember { mutableStateMapOf<String, Boolean>() }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var newFolderParent by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(onClick = onDismiss),
        )
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.82f)
                .align(Alignment.CenterStart)
                .background(Color(0xFF0A121C))
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text("Memoria", color = Cyan, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(18.dp))
            DrawerFolderRow(
                label = "Tutte le note",
                count = records.size,
                selected = selected == null,
                hasChildren = false,
                expanded = false,
                onToggleExpand = {},
                onClick = onSelectAll,
                onRename = null,
                onDelete = null,
                onAddSubfolder = null,
            )
            Spacer(Modifier.size(14.dp))
            Text(
                "CARTELLE",
                color = MUTED,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            tree.forEach { node ->
                val autoExpand = selected == node.path || selected?.startsWith("${node.path}/") == true
                val expanded = expandedOverride[node.path] ?: autoExpand
                val directCount = records.count { it.category.ifBlank { UNCATEGORIZED } == node.path }
                DrawerFolderRow(
                    label = node.name,
                    count = directCount,
                    selected = selected == node.path,
                    hasChildren = node.children.isNotEmpty(),
                    expanded = expanded,
                    onToggleExpand = { expandedOverride[node.path] = !expanded },
                    onClick = { onSelectCategory(node.path) },
                    onRename = { renameTarget = node.path },
                    onDelete = { deleteTarget = node.path },
                    onAddSubfolder = { newFolderParent = node.path },
                )
                if (expanded) {
                    node.children.forEach { child ->
                        val childCount = records.count { it.category == child.path }
                        DrawerFolderRow(
                            label = child.name,
                            count = childCount,
                            selected = selected == child.path,
                            hasChildren = false,
                            expanded = false,
                            indent = 22.dp,
                            onToggleExpand = {},
                            onClick = { onSelectCategory(child.path) },
                            onRename = { renameTarget = child.path },
                            onDelete = { deleteTarget = child.path },
                            onAddSubfolder = null,
                        )
                    }
                }
            }
            val uncategorizedCount = records.count { it.category.isBlank() }
            if (uncategorizedCount > 0) {
                DrawerFolderRow(
                    label = UNCATEGORIZED,
                    count = uncategorizedCount,
                    selected = selected == UNCATEGORIZED,
                    hasChildren = false,
                    expanded = false,
                    onToggleExpand = {},
                    onClick = { onSelectCategory(UNCATEGORIZED) },
                    onRename = null,
                    onDelete = null,
                    onAddSubfolder = null,
                )
            }
            Spacer(Modifier.size(18.dp))
            HorizontalDivider(color = MUTED.copy(alpha = 0.2f))
            Spacer(Modifier.size(10.dp))
            NewFolderField(
                parentHint = newFolderParent,
                onClearParentHint = { newFolderParent = null },
                onCreate = onSelectCategory,
            )
        }
    }

    renameTarget?.let { path ->
        MemoryFolderRenameDialog(
            currentName = path.substringAfterLast('/'),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                val parent = path.substringBeforeLast('/', "")
                onRenameCategory(path, if (parent.isBlank()) newName else "$parent/$newName")
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { path ->
        MemoryFolderDeleteDialog(
            path = path,
            onDismiss = { deleteTarget = null },
            onConfirm = { onDeleteCategory(path); deleteTarget = null },
        )
    }
}

/** One row of the drawer's folder tree: an optional expand chevron, the name+count, and a "⋮" menu when editable. */
@Composable
private fun DrawerFolderRow(
    label: String,
    count: Int,
    selected: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    indent: Dp = 0.dp,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onAddSubfolder: (() -> Unit)?,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Cyan.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 10.dp + indent, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            Text(
                if (expanded) "▾" else "▸",
                color = MUTED,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = if (selected) Cyan else INK,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", color = MUTED, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 2.dp))
        if (onRename != null || onDelete != null) {
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Altre azioni", tint = MUTED, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (onRename != null) {
                        DropdownMenuItem(text = { Text("Rinomina") }, onClick = { showMenu = false; onRename() })
                    }
                    if (onAddSubfolder != null) {
                        DropdownMenuItem(text = { Text("Aggiungi sottocartella") }, onClick = { showMenu = false; onAddSubfolder() })
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text("Elimina", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/** "+ Nuova cartella" — a free-text field; a "/" in the name creates a subfolder directly, or [parentHint] pre-targets one from the row's own "⋮" menu. */
@Composable
private fun NewFolderField(parentHint: String?, onClearParentHint: () -> Unit, onCreate: (String) -> Unit) {
    var addingManually by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val showForm = addingManually || parentHint != null
    if (showForm) {
        Column {
            if (parentHint != null) {
                Text("In: $parentHint", color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = {
                        Text(
                            if (parentHint != null) "Nome sottocartella" else "Nome cartella (usa “/” per una sottocartella)",
                            color = MUTED,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = INK),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan.copy(alpha = 0.7f),
                        unfocusedBorderColor = MUTED.copy(alpha = 0.4f),
                        cursorColor = Cyan,
                        focusedTextColor = INK,
                        unfocusedTextColor = INK,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotBlank()) {
                            onCreate(if (parentHint != null) "$parentHint/$trimmed" else trimmed)
                            name = ""
                            addingManually = false
                            onClearParentHint()
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Crea", color = Cyan) }
            }
        }
    } else {
        Text(
            "+ Nuova cartella",
            color = Cyan,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { addingManually = true }
                .padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun MemoryFolderRenameDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rinomina cartella") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Rinomina") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun MemoryFolderDeleteDialog(path: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eliminare «$path»?") },
        text = { Text("Le note al suo interno non vengono cancellate: tornano semplicemente senza categoria.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Elimina", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
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

/** A colour-tinted note tile showing a plain preview (markup stripped) and its date; tap to edit. */
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
                // Markup-stripped so a tile never shows raw "**"/"[color=…]" —
                // the same rich text now written by the editor's toolbar.
                MemoryMarkup.plainText(record.text) + if (record.kind == MemoryKind.SENSITIVE) "  🔒" else "",
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
 * ... a note in foto"). A big title-less canvas plus a formatting toolbar that
 * inserts markup at the cursor/selection — not a live WYSIWYG editor, same
 * "insert at cursor" pattern already established for bold/italic/bullet — with
 * an "Anteprima" toggle that renders the real styled result via [MemoryMarkup]
 * (colour, highlight colour, font size, alignment, checkable checklist).
 *
 * [MemoryRecord.text] stores this rich markup as plain text (§ richiesta
 * esplicita dell'utente: "Togli sincronizzazione con obsidian e rimaniamo solo
 * su archivio locale" — the syntax is app-only now that Memoria no longer
 * mirrors to Obsidian, so it is free to include tags `==`/`**` alone couldn't
 * express, e.g. `[color=#RRGGBB]…[/color]`).
 */
@Composable
private fun MemoryNoteEditorScreen(
    title: String,
    initialText: String,
    initialKind: MemoryKind,
    initialCategory: String,
    initialTheme: String,
    initialSpacing: String,
    allowTemporary: Boolean,
    showDelete: Boolean,
    enabled: Boolean,
    allCategories: List<String>,
    topics: List<String> = emptyList(),
    people: List<String> = emptyList(),
    dates: List<String> = emptyList(),
    customBackgrounds: List<String>,
    backgroundStore: NoteBackgroundStore,
    onImportBackground: () -> Unit,
    onDeleteBackground: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, MemoryKind, String, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(initialText)) }
    var kind by remember { mutableStateOf(initialKind) }
    var category by remember { mutableStateOf(initialCategory) }
    var theme by remember { mutableStateOf(MemoryNoteThemes.sanitize(initialTheme)) }
    var spacing by remember { mutableStateOf(MemoryLineSpacing.sanitize(initialSpacing)) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val accent = Cyan

    // Exiting must never lose what was typed — on purpose (back arrow) or by
    // accident (system back button/gesture, e.g. Honor's edge swipe: § richiesta
    // esplicita dell'utente, "sia volontariamente che per errore"). Both paths
    // now save first (skipping only a genuinely blank note, same rule as the
    // "Salva" button) instead of discarding silently.
    val exitSaving: () -> Unit = {
        if (field.text.isNotBlank()) onSave(field.text, kind, category, theme, spacing)
        onDismiss()
    }
    BackHandler(onBack = exitSaving)

    Box(Modifier.fillMaxSize().imePadding()) {
        val backgroundRes = customBackgroundRes(theme)
        // "user:" ids resolve to a file in app-private storage, never a
        // bundled drawable — null while decoding or if the file was since
        // deleted, in which case this simply falls through to the plain
        // colour background below (§ NoteBackgroundStore: no dangling ref).
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
            // A dark scrim over the artwork: the app's text colour (INK) is a
            // light tone made for a dark background, and these images are
            // mostly light "paper" — without it the text would be nearly
            // unreadable. The artwork stays recognisable, just dimmed.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC050C16), Color(0xB3081420), Color(0xCC03080E)),
                        ),
                    ),
            )
        } else {
            Box(Modifier.fillMaxSize().background(themeBackgroundBrush(theme)))
        }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = exitSaving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Salva ed esci", tint = INK)
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
                    onClick = { if (field.text.isNotBlank()) onSave(field.text, kind, category, theme, spacing) },
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CategorySelector(category, allCategories, enabled) { category = it }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showPreview = !showPreview }) {
                    Text(if (showPreview) "Modifica" else "Anteprima", color = accent)
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
            StructuredFields(topics, people, dates)
            Spacer(Modifier.size(12.dp))
            if (showPreview) {
                MarkupPreview(
                    raw = field.text,
                    accent = accent,
                    spacing = spacing,
                    onToggleLine = { index -> field = field.copy(text = toggleChecklistLine(field.text, index)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = { Text("Scrivi qui…", color = MUTED) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = INK, lineHeight = spacingLineHeight(spacing)),
                    visualTransformation = MarkupVisualTransformation,
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
            Spacer(Modifier.size(24.dp))
        }
    }
}

/**
 * Titolo/Sottotitolo/grassetto/corsivo/sottolineato/evidenzia/colore testo/
 * colore evidenziatore/dimensione/elenchi/checklist/divisore/allineamento —
 * plain text glyphs rather than Material's "extended" icon set (FormatBold/…),
 * which lives in a separate artifact this project doesn't otherwise depend on
 * and couldn't be verified against a compiler in this environment; glyphs
 * match the project's existing convention (the "▾"/"▸" chevrons elsewhere,
 * emoji document glyphs in Archivio) and carry zero dependency risk.
 * Titolo/Sottotitolo/colore/dimensione insert markup rather than actually
 * restyling the text as you type — [OutlinedTextField] renders one uniform
 * style, so live WYSIWYG would need a from-scratch rich-text editor; "Anteprima"
 * is where the real styled result is shown, via [MemoryMarkup].
 */
@Composable
private fun FormattingToolbar(
    onTitle: () -> Unit,
    onSubtitle: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onHighlight: () -> Unit,
    onTextColor: (String) -> Unit,
    onHighlightColor: (String) -> Unit,
    onFontSize: (String) -> Unit,
    onBullet: () -> Unit,
    onNumbered: () -> Unit,
    onChecklist: () -> Unit,
    onDivider: () -> Unit,
    onAlignStart: () -> Unit,
    onAlignCenter: () -> Unit,
    onAlignEnd: () -> Unit,
    spacing: String,
    onSpacing: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphButton(onClick = onTitle) { Text("T1", color = INK, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        GlyphButton(onClick = onSubtitle) { Text("T2", color = INK, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        GlyphButton(onClick = onBold) { Text("B", color = INK, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        GlyphButton(onClick = onItalic) { Text("I", color = INK, fontStyle = FontStyle.Italic, fontSize = 16.sp) }
        GlyphButton(onClick = onUnderline) { Text("U", color = INK, textDecoration = TextDecoration.Underline, fontSize = 16.sp) }
        GlyphButton(onClick = onHighlight) { Text("H", color = Color(0xFFF3C34C), fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        SwatchPickerButton(glyph = "A", swatches = SWATCHES, onPick = onTextColor)
        SwatchPickerButton(glyph = "▧", swatches = SWATCHES, onPick = onHighlightColor)
        SizePickerButton(onPick = onFontSize)
        SpacingPickerButton(current = spacing, onPick = onSpacing)
        GlyphButton(onClick = onBullet) { Text("•", color = INK, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        GlyphButton(onClick = onNumbered) { Text("1.", color = INK, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        GlyphButton(onClick = onChecklist) { Text("☑", color = INK, fontSize = 16.sp) }
        GlyphButton(onClick = onDivider) { Text("―", color = INK, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        GlyphButton(onClick = onAlignStart) { Text("Sx", color = INK, fontSize = 12.sp) }
        GlyphButton(onClick = onAlignCenter) { Text("Cn", color = INK, fontSize = 12.sp) }
        GlyphButton(onClick = onAlignEnd) { Text("Dx", color = INK, fontSize = 12.sp) }
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

/** Generic swatches for text colour / highlight colour — not brand-specific, just useful choices. */
private val SWATCHES: List<Pair<String, String>> = listOf(
    "#FF5C5C" to "Rosso", "#FFA352" to "Arancio", "#FFE156" to "Giallo", "#6BE585" to "Verde",
    "#5CD6E8" to "Ciano", "#5C9CFF" to "Blu", "#C57CFF" to "Viola", "#F2F2F2" to "Bianco",
)

/** A toolbar button that opens a dropdown of colour swatches. */
@Composable
private fun SwatchPickerButton(glyph: String, swatches: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlyphButton(onClick = { open = true }) { Text(glyph, color = INK, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            swatches.forEach { (hex, name) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(16.dp).clip(CircleShape).background(parseHex(hex)))
                            Spacer(Modifier.width(8.dp))
                            Text(name)
                        }
                    },
                    onClick = { open = false; onPick(hex) },
                )
            }
        }
    }
}

/** A toolbar button that opens a dropdown of the four font-size steps. */
@Composable
private fun SizePickerButton(onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlyphButton(onClick = { open = true }) { Text("Aa", color = INK, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                SizeStep.SMALL to "Piccolo",
                SizeStep.MEDIUM to "Medio",
                SizeStep.LARGE to "Grande",
                SizeStep.EXTRA_LARGE to "Molto grande",
            ).forEach { (step, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(step.tag) })
            }
        }
    }
}

/**
 * "Spaziatura" (§ richiesta esplicita dell'utente, l'ultima voce mancante
 * dell'elenco originale: "centratura e spaziatura del testo") — un'altezza
 * di riga per nota, non un concetto per-carattere: né Markdown né
 * l'editor a testo semplice hanno una nozione di letter-spacing, quindi
 * questa è l'interpretazione onesta e realizzabile della richiesta.
 */
@Composable
private fun SpacingPickerButton(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlyphButton(onClick = { open = true }) { Text("≡", color = INK, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                MemoryLineSpacing.COMPACT to "Compatta",
                MemoryLineSpacing.DEFAULT to "Normale",
                MemoryLineSpacing.WIDE to "Ampia",
            ).forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(if (value == current) "✓ $label" else label) },
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}

private fun spacingLineHeight(spacing: String): androidx.compose.ui.unit.TextUnit = when (spacing) {
    MemoryLineSpacing.COMPACT -> 1.1.em
    MemoryLineSpacing.WIDE -> 1.9.em
    else -> 1.4.em
}

/**
 * Placeholder text used when a toolbar button is tapped with nothing selected.
 * Earlier, an empty selection just inserted an unpaired-looking marker pair
 * with the cursor parked invisibly between them — tapping a second button
 * before typing anything produced garbled, mismatched markup (a real bug the
 * user hit and reported from a screenshot). Now the placeholder is inserted
 * pre-selected, so it reads clearly and typing immediately replaces it.
 */
private const val MARKUP_PLACEHOLDER = "testo"

/** Wraps the selection — or a selected placeholder at the cursor — in [marker], e.g. "**bold**". */
private fun wrapSelection(value: TextFieldValue, marker: String): TextFieldValue =
    wrapSelectionWith(value, marker, marker)

/** Inserts [prefix] at the start of the line the cursor is on, e.g. "- " or "1. ". */
private fun prefixLine(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val cursor = value.selection.min
    val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
    val newText = text.substring(0, lineStart) + prefix + text.substring(lineStart)
    return value.copy(text = newText, selection = TextRange(cursor + prefix.length))
}

/** Like [wrapSelection] but with a different opening/closing mark, e.g. "<u>"/"</u>". */
private fun wrapSelectionWith(value: TextFieldValue, prefix: String, suffix: String): TextFieldValue {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    if (start == end) {
        val newText = text.substring(0, start) + prefix + MARKUP_PLACEHOLDER + suffix + text.substring(end)
        val selStart = start + prefix.length
        return value.copy(text = newText, selection = TextRange(selStart, selStart + MARKUP_PLACEHOLDER.length))
    }
    val newText = text.substring(0, start) + prefix + text.substring(start, end) + suffix + text.substring(end)
    return value.copy(text = newText, selection = TextRange(end + prefix.length + suffix.length))
}

/** Inserts a Markdown thematic break ("---") as its own paragraph at the cursor. */
private fun insertDivider(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.min
    val insert = "\n\n---\n\n"
    val newText = text.substring(0, cursor) + insert + text.substring(cursor)
    return value.copy(text = newText, selection = TextRange(cursor + insert.length))
}

/** Sets (or clears) the current line's alignment marker — "", "[center]" or "[right]". */
private fun setLineAlign(value: TextFieldValue, tag: String): TextFieldValue {
    val text = value.text
    val cursor = value.selection.min
    val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
    val rest = text.substring(lineStart)
    val stripped = when {
        rest.startsWith("[center]") -> rest.removePrefix("[center]")
        rest.startsWith("[right]") -> rest.removePrefix("[right]")
        else -> rest
    }
    val removedLength = rest.length - stripped.length
    val newText = text.substring(0, lineStart) + tag + stripped
    val delta = tag.length - removedLength
    return value.copy(text = newText, selection = TextRange((cursor + delta).coerceIn(lineStart, newText.length)))
}

/** Toggles a `- [ ] `/`- [x] ` checklist marker on one physical line, by index — used by the tappable checkbox in preview. */
private fun toggleChecklistLine(raw: String, lineIndex: Int): String {
    val lines = raw.split("\n").toMutableList()
    if (lineIndex !in lines.indices) return raw
    val line = lines[lineIndex]
    val trimmedStart = line.trimStart()
    val indent = line.substring(0, line.length - trimmedStart.length)
    lines[lineIndex] = when {
        trimmedStart.startsWith("- [ ] ") -> indent + "- [x] " + trimmedStart.drop(6)
        trimmedStart.startsWith("- [x] ") || trimmedStart.startsWith("- [X] ") -> indent + "- [ ] " + trimmedStart.drop(6)
        else -> line
    }
    return lines.joinToString("\n")
}

/** Renders [raw] via [MemoryMarkup] — the real styled result, not the raw markup syntax. */
@Composable
private fun MarkupPreview(
    raw: String,
    accent: Color,
    spacing: String,
    onToggleLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = remember(raw) { MemoryMarkup.parse(raw) }
    // "Spaziatura" (§ richiesta esplicita dell'utente) — in a line-per-Row
    // layout like this, the natural equivalent of line spacing is the gap
    // between rows, not a per-character property nothing here has.
    val gap = when (spacing) {
        MemoryLineSpacing.COMPACT -> 2.dp
        MemoryLineSpacing.WIDE -> 14.dp
        else -> 6.dp
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
        lines.forEachIndexed { index, line ->
            if (line.isDivider) {
                HorizontalDivider(color = MUTED.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))
                return@forEachIndexed
            }
            val fontSize = if (line.isHeading1) 22.sp else if (line.isHeading2) 18.sp else 16.sp
            val weight = if (line.isHeading1 || line.isHeading2) FontWeight.Bold else FontWeight.Normal
            val checked = line.isChecklistChecked
            val prefix = when {
                checked != null -> null
                line.isBullet -> "•  "
                line.isNumbered -> "${index + 1}.  "
                else -> null
            }
            val annotated = remember(line) {
                buildAnnotatedString {
                    append(line.text)
                    line.runs.forEach { addStyle(it.style.toSpanStyle(), it.start, it.end) }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = when (line.align) {
                    MarkupAlign.CENTER -> Arrangement.Center
                    MarkupAlign.END -> Arrangement.End
                    MarkupAlign.START -> Arrangement.Start
                },
            ) {
                if (checked != null) {
                    Text(
                        if (checked) "☑" else "☐",
                        color = if (checked) accent else INK,
                        fontSize = fontSize,
                        modifier = Modifier.clickable { onToggleLine(index) }.padding(end = 8.dp),
                    )
                } else if (prefix != null) {
                    Text(prefix, color = INK, fontSize = fontSize)
                }
                Text(
                    annotated,
                    color = INK,
                    fontSize = fontSize,
                    fontWeight = weight,
                    textDecoration = if (checked == true) TextDecoration.LineThrough else null,
                )
            }
        }
        if (lines.all { it.text.isBlank() && !it.isDivider }) {
            Text("Anteprima vuota.", color = MUTED, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun InlineStyle.toSpanStyle(): SpanStyle = when (this) {
    InlineStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    InlineStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    InlineStyle.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
    is InlineStyle.Highlight -> SpanStyle(background = parseHex(colorHex))
    is InlineStyle.TextColor -> SpanStyle(color = parseHex(colorHex))
    is InlineStyle.FontSize -> SpanStyle(
        fontSize = when (step) {
            SizeStep.SMALL -> 12.sp
            SizeStep.MEDIUM -> 16.sp
            SizeStep.LARGE -> 20.sp
            SizeStep.EXTRA_LARGE -> 26.sp
        },
    )
}

private fun parseHex(hex: String): Color = runCatching {
    val rgb = hex.removePrefix("#").toLong(16)
    Color((0xFF000000L or rgb).toInt())
}.getOrDefault(Color.White)

/**
 * Live "no visible markup characters" formatting in the edit field itself
 * (§ richiesta esplicita dell'utente: "quando seleziono per esempio in
 * grassetto mi fa asterischi, non li voglio, voglio che mi dia esattamente
 * l'effetto richiesto"). A [VisualTransformation] is exactly Compose's tool
 * for this: the underlying [TextFieldValue] the toolbar/`onSave` operate on
 * still holds the raw markup text unchanged (so storage, the toolbar's
 * insert-at-cursor helpers, and "Anteprima" all keep working exactly as
 * before) — only what's DRAWN on screen, and where the cursor/selection
 * visually land, go through [MemoryMarkup.transform]. Block markers
 * (headings/bullets/checklist/divider/alignment) are a deliberate, documented
 * scope boundary of [MemoryMarkup.transform] and stay visible as typed —
 * "Anteprima" is still where those render fully.
 */
private object MarkupVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val result = MemoryMarkup.transform(text.text)
        val annotated = buildAnnotatedString {
            append(result.displayText)
            result.runs.forEach { addStyle(it.style.toSpanStyle(), it.start, it.end) }
        }
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = result.displayOffset(offset)
            override fun transformedToOriginal(offset: Int): Int = result.rawOffset(offset)
        }
        return TransformedText(annotated, offsetMapping)
    }
}

/**
 * The note's background theme — solid-colour swatches (§ richiesta esplicita
 * dell'utente: "cambiare il tema dello sfondo della nota con molti temi
 * anche molto ispirati ad anime"; risposta alla domanda di chiarimento:
 * "Temi generici, nessun riferimento specifico" — gradienti generici scelti
 * per nome/atmosfera, non arte con licenza) plus custom image backgrounds
 * the user provided. Both rows now live behind ONE collapsed-by-default
 * toggle (§ richiesta esplicita, giro successivo: "gli sfondi sia per
 * colori che con immagini prendono troppo spazio, li volevo con sezione
 * apribile" — in precedenza solo le immagini erano dietro un toggle, i
 * colori restavano sempre visibili). Collapsed, it shows just a small
 * preview of the current choice, so the note content stays the dominant
 * thing on screen instead of being crowded out.
 */
@Composable
private fun ThemeSelector(
    current: String,
    onSelect: (String) -> Unit,
    customUserBackgrounds: List<String>,
    backgroundStore: NoteBackgroundStore,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", color = Cyan, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text("Sfondo nota", color = MUTED, fontSize = 12.sp, modifier = Modifier.weight(1f))
            ThemePreviewSwatch(current, backgroundStore)
        }
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MemoryNoteThemes.GRADIENTS.forEach { id ->
                    val selected = current == id
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(themeSwatchColor(id))
                            .then(if (selected) Modifier.border(2.dp, Cyan, CircleShape) else Modifier)
                            .clickable { onSelect(id) },
                    )
                }
            }
            Text(
                "Sfondi personalizzati",
                color = MUTED,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemoryNoteThemes.IMAGES.forEach { id ->
                    val res = customBackgroundRes(id) ?: return@forEach
                    val selected = current == id
                    Image(
                        painter = painterResource(res),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 52.dp, height = 78.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (selected) Modifier.border(2.dp, Cyan, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { onSelect(id) },
                    )
                }
            }
            // Sfondi importati dalla galleria (§ richiesta esplicita
            // dell'utente) — mai bundlati nell'app, solo storage app-privato.
            // Il "+" è sempre il primo elemento così resta raggiungibile
            // anche con zero sfondi già importati.
            Text(
                "I tuoi sfondi",
                color = MUTED,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 52.dp, height = 78.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MUTED.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onImport),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Cyan, fontSize = 20.sp)
                }
                customUserBackgrounds.forEach { id ->
                    val bitmap = rememberUserBackgroundBitmap(backgroundStore, id) ?: return@forEach
                    val selected = current == id
                    Box(
                        Modifier.size(width = 52.dp, height = 78.dp),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .then(if (selected) Modifier.border(2.dp, Cyan, RoundedCornerShape(8.dp)) else Modifier)
                                .clickable { onSelect(id) },
                        )
                        // Elimina l'immagine importata — una nota che la usava
                        // torna semplicemente allo sfondo colore di default
                        // (nessun riferimento pendente, gestito da onDelete).
                        Box(
                            Modifier
                                .padding(3.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(0xCC03080E))
                                .clickable { onDelete(id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✕", color = Color.White, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A small always-visible preview of the currently selected background, shown next to the collapsed toggle. */
@Composable
private fun ThemePreviewSwatch(current: String, backgroundStore: NoteBackgroundStore) {
    val imageRes = customBackgroundRes(current)
    val userBitmap = if (imageRes == null && MemoryNoteThemes.isUserImage(current)) rememberUserBackgroundBitmap(backgroundStore, current) else null
    when {
        imageRes != null -> Image(
            painter = painterResource(imageRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(20.dp).clip(CircleShape),
        )
        userBitmap != null -> Image(
            bitmap = userBitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(20.dp).clip(CircleShape),
        )
        else -> Box(Modifier.size(20.dp).clip(CircleShape).background(themeSwatchColor(current)))
    }
}

/**
 * Decodes a user-imported background off the main thread; null while loading
 * or if the file is gone. `remember` + `LaunchedEffect` rather than
 * `produceState` — Compose lint's `ProduceStateDoesNotAssignValue` check kept
 * flagging this as a false positive (CI-caught, twice) regardless of how the
 * `value = …` assignment inside the producer lambda was shaped; this is the
 * equally-idiomatic, lint-clean alternative for the same async-load pattern.
 */
@Composable
private fun rememberUserBackgroundBitmap(store: NoteBackgroundStore, id: String): ImageBitmap? {
    var bitmap by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        bitmap = withContext(Dispatchers.IO) {
            store.file(id)?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
        }
    }
    return bitmap
}

private fun themeSwatchColor(id: String): Color = when (id) {
    "sunset" -> Color(0xFF8B2E4A)
    "ocean" -> Color(0xFF0E5C73)
    "forest" -> Color(0xFF1F5C33)
    "lavanda" -> Color(0xFF5A4A99)
    "rosa" -> Color(0xFF9A3D6B)
    "notte" -> Color(0xFF141A33)
    "menta" -> Color(0xFF1E8F73)
    "pesca" -> Color(0xFF9A6A2E)
    "ardesia" -> Color(0xFF4A5568)
    else -> Color(0xFF3FD8F0)
}

private fun themeBackgroundBrush(id: String): Brush = when (id) {
    "sunset" -> Brush.verticalGradient(listOf(Color(0xFF2E0B18), Color(0xFF4A1729), Color(0xFF1C0710)))
    "ocean" -> Brush.verticalGradient(listOf(Color(0xFF031A22), Color(0xFF0A3644), Color(0xFF021016)))
    "forest" -> Brush.verticalGradient(listOf(Color(0xFF091D12), Color(0xFF12331D), Color(0xFF05120A)))
    "lavanda" -> Brush.verticalGradient(listOf(Color(0xFF1B1530), Color(0xFF2A2049), Color(0xFF110D22)))
    "rosa" -> Brush.verticalGradient(listOf(Color(0xFF2E0F1E), Color(0xFF461B32), Color(0xFF1A0813)))
    "notte" -> Brush.verticalGradient(listOf(Color(0xFF04050C), Color(0xFF090C1A), Color(0xFF020207)))
    "menta" -> Brush.verticalGradient(listOf(Color(0xFF07211B), Color(0xFF0E3A30), Color(0xFF041410)))
    "pesca" -> Brush.verticalGradient(listOf(Color(0xFF2E1D0E), Color(0xFF473018), Color(0xFF190F07)))
    "ardesia" -> Brush.verticalGradient(listOf(Color(0xFF161A20), Color(0xFF232A33), Color(0xFF0C0F13)))
    else -> Brush.verticalGradient(listOf(Color(0xFF050C16), Color(0xFF081420), Color(0xFF03080E)))
}

/**
 * Drawable for a custom image background, or null when [id] isn't one (a
 * gradient id, or a not-yet-recognised value). The nine images the user
 * supplied — generic Japanese/anime-aesthetic paper-note frames, no
 * identifiable copyrighted characters or franchise logos — cropped from
 * their reference grid (`docs/design` was not the source; see CLAUDE.md).
 */
private fun customBackgroundRes(id: String): Int? = when (MemoryNoteThemes.imageKey(id)) {
    "sakura_torii" -> R.drawable.memnote_bg_sakura_torii
    "coast_bridge" -> R.drawable.memnote_bg_coast_bridge
    "sunset_bamboo" -> R.drawable.memnote_bg_sunset_bamboo
    "night_city" -> R.drawable.memnote_bg_night_city
    "cat_leaves" -> R.drawable.memnote_bg_cat_leaves
    "clouds_hat" -> R.drawable.memnote_bg_clouds_hat
    "red_clouds" -> R.drawable.memnote_bg_red_clouds
    "wave_blue" -> R.drawable.memnote_bg_wave_blue
    "wisteria_katana" -> R.drawable.memnote_bg_wisteria_katana
    else -> null
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

/**
 * A tap-to-change category chip. Opens [MemoryFolderPickerDialog] instead of
 * a dropdown limited to [MemoryCategories.CANONICAL] — the user can type a
 * brand-new folder name here too (§ richiesta esplicita dell'utente: "non
 * solo le categorie AI fisse attuali").
 */
@Composable
private fun CategorySelector(current: String, categories: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled) {
        Text("Categoria: " + current.ifBlank { "Senza categoria" })
    }
    if (open) {
        MemoryFolderPickerDialog(
            categories = categories,
            current = current,
            onDismiss = { open = false },
            onPick = { open = false; onSelect(it) },
        )
    }
}

/** Pick an existing category or type a brand-new one — same shape as Archivio's FolderPickerDialog. */
@Composable
private fun MemoryFolderPickerDialog(categories: List<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cartella") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Senza categoria",
                    modifier = Modifier.fillMaxWidth().clickable { onPick("") }.padding(vertical = 8.dp),
                    fontWeight = if (current.isBlank()) FontWeight.Bold else FontWeight.Normal,
                )
                categories.forEach { c ->
                    Text(
                        c,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 8.dp),
                        fontWeight = if (current == c) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nuova cartella") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onPick(newName.trim()) }, enabled = newName.isNotBlank()) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

/**
 * Redesigned (§ richiesta esplicita dell'utente: "Migliora anche design dei
 * tasti categoria permanente e sensibile") as tappable pills with a semantic
 * colour and glyph each — Sensibile is always red regardless of the active
 * app theme (a real signal, not a brand colour), so it reads distinctly from
 * Permanente/Temporaneo even when they'd otherwise share the theme accent.
 * A plain Row (not Button/OutlinedButton) with explicit padding, same fix as
 * before for the Italian-word-wrap bug — Material's default button padding
 * is too wide for a third-width pill.
 */
@Composable
private fun KindSelector(selected: MemoryKind, onSelect: (MemoryKind) -> Unit, includeTemporary: Boolean) {
    val kinds = if (includeTemporary) MemoryKind.entries else listOf(MemoryKind.PERMANENT, MemoryKind.SENSITIVE)
    val accent = Cyan
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { kind ->
            val kindColor = when (kind) {
                MemoryKind.TEMPORARY -> MUTED
                MemoryKind.PERMANENT -> accent
                MemoryKind.SENSITIVE -> SensitiveRed
            }
            val isSelected = kind == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) kindColor.copy(alpha = 0.20f) else Color.Transparent)
                    .border(1.dp, kindColor.copy(alpha = if (isSelected) 0.9f else 0.35f), RoundedCornerShape(14.dp))
                    .clickable { onSelect(kind) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(kind.glyph(), fontSize = 13.sp)
                Spacer(Modifier.width(5.dp))
                Text(
                    kind.label(),
                    maxLines = 1,
                    fontSize = 12.sp,
                    softWrap = false,
                    color = if (isSelected) kindColor else INK,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private val SensitiveRed = Color(0xFFE05B4C)

private fun MemoryKind.glyph(): String = when (this) {
    MemoryKind.TEMPORARY -> "⏱"
    MemoryKind.PERMANENT -> "📌"
    MemoryKind.SENSITIVE -> "🔒"
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
