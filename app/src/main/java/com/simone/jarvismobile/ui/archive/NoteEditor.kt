package com.simone.jarvismobile.ui.archive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.simone.jarvismobile.core.memory.MemoryLineSpacing
import com.simone.jarvismobile.core.memory.MemoryNoteThemes
import com.simone.jarvismobile.core.notes.NoteEntry
import com.simone.jarvismobile.core.notes.NoteRef
import com.simone.jarvismobile.core.notes.NoteSaveDecision
import com.simone.jarvismobile.core.notes.NoteSavePolicy
import com.simone.jarvismobile.core.notes.NoteSource
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of persisting one note through the store that owns it. */
sealed interface NoteSaveResult {
    data class Saved(val ref: NoteRef) : NoteSaveResult
    data class Failed(val message: String) : NoteSaveResult
}

private enum class SaveStatus { IDLE, SAVING, SAVED, ERROR }

private data class EditorSnapshot(
    val title: String, val content: String, val folder: String,
    val pinned: Boolean, val theme: String, val spacing: String,
)

/**
 * Full-screen note editor (BETA 3). Same formatting/theme/background machinery
 * as before, but saving is now safe by construction:
 *  - the note is autosaved ~1s after the last keystroke, when the app goes to
 *    background, and again on every way out (arrow, system back);
 *  - a blank title never blocks saving (the title is derived from the text,
 *    see [NoteSavePolicy]) and a brand-new empty note is never created;
 *  - saves are serialised and carry the id of the note being edited, so a
 *    rapid edit can neither duplicate it nor touch another note;
 *  - the screen only closes once the final save succeeded; a failure stays
 *    visible ("Non salvato") and offers an explicit "Esci senza salvare";
 *  - deleting asks for confirmation.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun NoteEditorScreen(
    note: NoteEntry?,
    folders: List<String>,
    customBackgrounds: List<String>,
    backgroundStore: NoteBackgroundStore,
    onImportBackground: () -> Unit,
    onDeleteBackground: (String) -> Unit,
    onSave: suspend (ref: NoteRef?, title: String, content: String, folder: String, pinned: Boolean, theme: String, spacing: String) -> NoteSaveResult,
    onDelete: ((NoteRef) -> Unit)?,
    onClose: () -> Unit,
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var field by remember { mutableStateOf(TextFieldValue(note?.content ?: "")) }
    var folder by remember { mutableStateOf(note?.folder ?: "") }
    var pinned by remember { mutableStateOf(note?.pinned ?: false) }
    var theme by remember { mutableStateOf(MemoryNoteThemes.sanitize(note?.theme ?: "")) }
    var spacing by remember { mutableStateOf(MemoryLineSpacing.sanitize(note?.spacing ?: "")) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    var baseline by remember { mutableStateOf(note) }
    var ref by remember { mutableStateOf(note?.ref) }
    var status by remember { mutableStateOf(SaveStatus.IDLE) }
    var errorText by remember { mutableStateOf("") }
    val saveLock = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val titleEditable = note?.ref?.source != NoteSource.MEMORIA
    val canPin = note?.canPin ?: true

    /** Persists if anything changed. true = nothing left unsaved. */
    suspend fun persist(): Boolean = saveLock.withLock {
        val decision = NoteSavePolicy.decide(baseline, if (titleEditable) title else "", field.text, folder, pinned, theme, spacing)
        if (decision !is NoteSaveDecision.Save) return@withLock status != SaveStatus.ERROR
        status = SaveStatus.SAVING
        when (val r = onSave(ref, decision.title, decision.content, folder, pinned, theme, spacing)) {
            is NoteSaveResult.Saved -> {
                ref = r.ref
                baseline = NoteEntry(
                    ref = r.ref, title = decision.title, content = decision.content, folder = folder,
                    pinned = pinned, sensitive = note?.sensitive ?: false, theme = theme, spacing = spacing,
                    createdAt = 0, updatedAt = 0,
                )
                status = SaveStatus.SAVED
                true
            }
            is NoteSaveResult.Failed -> {
                status = SaveStatus.ERROR
                errorText = r.message
                false
            }
        }
    }

    fun leave() {
        scope.launch { if (persist()) onClose() }
    }

    BackHandler { leave() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { scope.launch { persist() } }
    LaunchedEffect(Unit) {
        snapshotFlow { EditorSnapshot(title, field.text, folder, pinned, theme, spacing) }
            .debounce(1_000)
            .collect { persist() }
    }

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
                IconButton(onClick = ::leave) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Salva ed esci", tint = Ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (note == null) "Nuova nota" else "Modifica nota",
                        color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when (status) {
                            SaveStatus.IDLE -> "Salvataggio automatico"
                            SaveStatus.SAVING -> "Salvataggio…"
                            SaveStatus.SAVED -> "Salvato"
                            SaveStatus.ERROR -> "Non salvato"
                        },
                        color = if (status == SaveStatus.ERROR) MaterialTheme.colorScheme.error else Muted,
                        fontSize = 11.sp,
                    )
                }
                if (onDelete != null && ref != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Elimina", tint = Muted)
                    }
                }
                if (canPin) {
                    IconButton(onClick = { pinned = !pinned }) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "In primo piano",
                            tint = if (pinned) Gold else Muted,
                        )
                    }
                }
            }
            if (status == SaveStatus.ERROR) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { persist() } }) { Text("Riprova", color = Cyan) }
                    TextButton(onClick = onClose) { Text("Esci senza salvare", color = Muted) }
                }
            }
            Spacer(Modifier.size(8.dp))
            if (titleEditable) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Titolo", color = Muted, fontSize = 22.sp) },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = Ink),
                    colors = borderlessFieldColors(),
                    singleLine = true,
                )
            } else {
                Text(
                    "Nota di Memoria: il titolo è la prima riga" + if (note?.sensitive == true) " · contiene dati sensibili" else "",
                    color = Muted, fontSize = 12.sp,
                )
            }
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare la nota?") },
            text = { Text("L'operazione non si può annullare.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val target = ref
                    if (target != null) onDelete?.invoke(target)
                    onClose()
                }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
}
