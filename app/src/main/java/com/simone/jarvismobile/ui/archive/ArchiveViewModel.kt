package com.simone.jarvismobile.ui.archive

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.archive.ArchiveListRepository
import com.simone.jarvismobile.archive.ArchiveRepository
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveList
import com.simone.jarvismobile.core.archive.ArchiveListItem
import com.simone.jarvismobile.core.archive.ArchiveStatus
import com.simone.jarvismobile.core.archive.ListItemStatus
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentStatus
import com.simone.jarvismobile.core.memory.MemoryRecord
import com.simone.jarvismobile.document.DocumentImportManager
import com.simone.jarvismobile.document.folder
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.NoteBackgroundStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the "Archivio" screen (spec §2/§4): create/read/update/delete NOTE and
 * TO_WATCH items plus generic lists (shopping + custom), plain text search.
 * The same [ArchiveRepository]/[ArchiveListRepository] the Pro Mode tools use
 * and [com.simone.jarvismobile.tools.CommandMatcher] calls in NORMAL mode, so
 * anything created here is immediately visible to both, and vice versa — one
 * store, not a UI-only copy of it (spec §11).
 */
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ArchiveRepository,
    private val lists: ArchiveListRepository,
    private val documentManager: DocumentImportManager,
    private val memoryIndex: MemoryIndex,
    val backgroundStore: NoteBackgroundStore,
) : ViewModel() {

    // Sfondi nota personalizzati (§ richiesta esplicita dell'utente: "deve
    // essere tutto personalizzabile: sfondo dietro") — stesso store globale
    // (file system app-privato, nessuno scoping per record) già usato da
    // Memoria: importare uno sfondo qui lo rende disponibile anche là, e
    // viceversa, invece di un secondo elenco separato.
    private val _customBackgrounds = MutableStateFlow<List<String>>(emptyList())
    val customBackgrounds: StateFlow<List<String>> = _customBackgrounds.asStateFlow()

    init {
        refreshBackgrounds()
    }

    fun importBackground(uri: Uri) {
        viewModelScope.launch {
            backgroundStore.import(uri)
            refreshBackgrounds()
        }
    }

    fun deleteBackground(id: String) {
        backgroundStore.delete(id)
        refreshBackgrounds()
    }

    private fun refreshBackgrounds() {
        _customBackgrounds.value = backgroundStore.list()
    }

    private val allItems: StateFlow<List<ArchiveItem>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Ready files/photos imported anywhere — the chat's "+", the old
     * Documenti tab, all of it — for the "Documenti" folder (§ richiesta
     * esplicita dell'utente: "se carico in chat file o foto... posso
     * trovarli anche in archivio"). Same [DocumentImportManager.documents]
     * the chat cards and Archivio documenti already read, not a copy.
     */
    val documents: StateFlow<List<DocumentRecord>> = documentManager.documents
        .map { docs -> docs.filter { it.status == DocumentStatus.READY }.sortedByDescending { it.importedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Imports still in flight (§ richiesta esplicita dell'utente: "quando
     * carica fai vedere barra di avanzamento") — [DocumentImportManager]
     * already ticks [DocumentRecord.status] through the real pipeline stages,
     * so this needs no separate progress plumbing: the screen maps status
     * straight to a fraction. FAILED is excluded too — that gets its own row
     * via [documentManager.documents] filtered on the failed status if the UI
     * wants it, not a progress bar.
     */
    val importingDocuments: StateFlow<List<DocumentRecord>> = documentManager.documents
        .map { docs ->
            docs.filter { it.status != DocumentStatus.READY && it.status != DocumentStatus.FAILED }
                .sortedByDescending { it.importedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Memoria's records, read-only here (§ "una cartella dedicata con le
     * note presenti nella sezione memoria divise per categorie") — editing
     * one still only happens in the Memoria screen itself, so this stays a
     * one-shot load rather than a second live store to keep in sync.
     * [refreshMemory] re-reads it, called when the Memoria folder is opened.
     */
    private val _memoryRecords = MutableStateFlow<List<MemoryRecord>>(emptyList())
    val memoryRecords: StateFlow<List<MemoryRecord>> = _memoryRecords

    init {
        refreshMemory()
    }

    fun refreshMemory() {
        viewModelScope.launch {
            _memoryRecords.value = runCatching { memoryIndex.listRecords() }.getOrDefault(emptyList())
                .sortedByDescending { it.updatedAt }
        }
    }

    /**
     * A `content://` URI for [record]'s app-private file, safe to hand to
     * another app (viewer, share sheet) — never a raw `file://` path, which
     * Android refuses to expose outside the app.
     */
    fun shareUri(record: DocumentRecord): Uri? = runCatching {
        val file = documentManager.localFile(record)
        if (!file.exists()) return null
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    /**
     * Imports files/photos picked from the phone's own system picker into the
     * "Documenti" folder (§ richiesta esplicita dell'utente: "archivio deve
     * avere impostazione per importare dal telefono dei file o foto") — the
     * exact same [DocumentImportManager.import] pipeline the chat's "+" already
     * uses, so an imported file is indexed/searchable the same way, not a
     * second copy mechanism. Never saved to the Obsidian vault by default
     * (matches the chat's plain attachment, not the "save to vault" option).
     * [folder] files every import straight into that folder — used when the
     * user is already browsing one, so "+" while inside "Lavoro" lands there
     * instead of the Documenti root.
     */
    fun importFromPhone(uris: List<Uri>, folder: String = "") {
        uris.forEach { documentManager.import(it, saveToVault = false, folder = folder) }
    }

    /** Removes an imported file from Archivio entirely (the pipeline record, not just the UI row). */
    fun removeDocument(record: DocumentRecord) {
        documentManager.remove(record.id)
    }

    /** Renames a document's display label (§ richiesta esplicita: "vorrei poterlo rinominare"). */
    fun renameDocument(record: DocumentRecord, newName: String) {
        documentManager.rename(record.id, newName)
    }

    /** Files a document under [folder] ("" clears it back to the Documenti root). */
    fun moveDocument(record: DocumentRecord, folder: String) {
        documentManager.moveToFolder(record.id, folder)
    }

    /**
     * How much of the device's storage JARVIS's local archive is using (§
     * richiesta esplicita dell'utente: "se l'archivio locale di jarvis ha un
     * limite di memoria inserisci barra"). There is no JARVIS-specific quota
     * — Android does not give an app a fixed allowance — so this is honest
     * about what it shows: bytes JARVIS itself has written (imported
     * documents/photos plus the local Memoria file) against the device's own
     * remaining free space, the real practical ceiling.
     */
    data class StorageUsage(val usedBytes: Long, val freeBytes: Long)

    val storageUsage: StateFlow<StorageUsage> = documents.map { docs ->
        val usedDocs = docs.sumOf { it.fileSize }
        val notesFile = java.io.File(context.filesDir, "memoria.md")
        val usedNotes = runCatching { if (notesFile.exists()) notesFile.length() else 0L }.getOrDefault(0L)
        val free = runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)
        StorageUsage(usedDocs + usedNotes, free)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUsage(0L, 0L))

    /** Every folder a document has been filed under, for the folder chip row — same pattern as [folders] for notes. */
    val documentFolders: StateFlow<List<String>> = documents
        .map { docs -> docs.mapNotNull { it.folder().takeIf { f -> f.isNotBlank() } }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Renames a Documenti folder (§ richiesta esplicita: "le cartelle non
     * sono modificabili") — every document under [oldPath] (including a
     * nested "oldPath/Child", since a subfolder is just a "/"-prefixed path,
     * § "non posso creare sottocartelle") is re-filed to [newPath].
     */
    fun renameDocumentFolder(oldPath: String, newPath: String) {
        val from = oldPath.trim()
        val to = newPath.trim()
        if (from.isBlank() || to.isBlank() || from == to) return
        documents.value.filter { it.folder() == from || it.folder().startsWith("$from/") }
            .forEach { doc ->
                val next = if (doc.folder() == from) to else to + doc.folder().removePrefix(from)
                documentManager.moveToFolder(doc.id, next)
            }
    }

    /** Un-files every document in [path] (and anything nested under it) back to the Documenti root — the files themselves are untouched. */
    fun deleteDocumentFolder(path: String) {
        val target = path.trim()
        if (target.isBlank()) return
        documents.value.filter { it.folder() == target || it.folder().startsWith("$target/") }
            .forEach { doc -> documentManager.moveToFolder(doc.id, "") }
    }

    /**
     * Renames an Appunti folder — every NOTE under [oldPath] (including a
     * nested "oldPath/Child") is re-filed to [newPath]. Same pattern as
     * [renameDocumentFolder], over [ArchiveItem.folder] instead of a tag.
     */
    fun renameNoteFolder(oldPath: String, newPath: String) {
        val from = oldPath.trim()
        val to = newPath.trim()
        if (from.isBlank() || to.isBlank() || from == to) return
        viewModelScope.launch {
            allItems.value.filter { it.kind == ArchiveKind.NOTE && (it.folder == from || it.folder.startsWith("$from/")) }
                .forEach { note ->
                    val next = if (note.folder == from) to else to + note.folder.removePrefix(from)
                    repository.update(note.id, folder = next)
                }
        }
    }

    /** Un-files every note in [path] (and anything nested under it) back to "Senza categoria" — the notes themselves are untouched. */
    fun deleteNoteFolder(path: String) {
        val target = path.trim()
        if (target.isBlank()) return
        viewModelScope.launch {
            allItems.value.filter { it.kind == ArchiveKind.NOTE && (it.folder == target || it.folder.startsWith("$target/")) }
                .forEach { note -> repository.update(note.id, folder = "") }
        }
    }

    private val allLists: StateFlow<List<ArchiveList>> = lists.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allListItems: StateFlow<List<ArchiveListItem>> = lists.observeAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Custom lists only — the shopping list gets its own dedicated tab, not a card in "Liste". */
    val customLists: StateFlow<List<ArchiveList>> = combine(allLists, allListItems) { ls, its ->
        ls.filter { it.type == com.simone.jarvismobile.core.archive.ArchiveListType.CUSTOM }
            .sortedBy { it.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shoppingItems: StateFlow<List<ArchiveListItem>> = combine(allLists, allListItems) { ls, its ->
        val shoppingId = ls.firstOrNull { it.type == com.simone.jarvismobile.core.archive.ArchiveListType.SHOPPING }?.id
        its.filter { it.listId == shoppingId }.sortedBy { it.order }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every list item across every list, reactive — the list-detail dialog filters by [ArchiveListItem.listId]. */
    val listItems: StateFlow<List<ArchiveListItem>> = allListItems

    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query

    val items: StateFlow<List<ArchiveItem>> = combine(allItems, query) { items, q ->
        val filtered = if (q.isBlank()) {
            items
        } else {
            items.filter { it.title.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true) }
        }
        filtered.sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every folder name a NOTE currently uses, for the folder chip row — sorted, no duplicates, no blanks. */
    val folders: StateFlow<List<String>> = allItems
        .map { items ->
            items.filter { it.kind == ArchiveKind.NOTE }
                .mapNotNull { it.folder.takeIf { f -> f.isNotBlank() } }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [documents] narrowed to [query] — the "Documenti" folder's own search. */
    val filteredDocuments: StateFlow<List<DocumentRecord>> = combine(documents, query) { docs, q ->
        docs.filter { q.isBlank() || it.displayName.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [memoryRecords] narrowed to [query] — the "Memoria" folder's own search. */
    val filteredMemory: StateFlow<List<MemoryRecord>> = combine(memoryRecords, query) { recs, q ->
        recs.filter { q.isBlank() || it.text.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ready files + notes, for the Home dashboard tile's count. */
    val itemCount: StateFlow<Int> = combine(documents, allItems) { docs, its ->
        docs.size + its.count { it.kind == ArchiveKind.NOTE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setQuery(value: String) {
        query.value = value
    }

    fun createNote(
        title: String,
        content: String,
        folder: String = "",
        pinned: Boolean = false,
        theme: String = "",
        spacing: String = "",
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.create(ArchiveKind.NOTE, title, content, folder = folder, pinned = pinned, theme = theme, spacing = spacing)
        }
    }

    fun createWatchItem(title: String, watchType: String, link: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.create(ArchiveKind.TO_WATCH, title, watchType = watchType, link = link) }
    }

    fun updateNote(
        item: ArchiveItem,
        title: String,
        content: String,
        folder: String,
        pinned: Boolean,
        theme: String,
        spacing: String,
    ) {
        viewModelScope.launch {
            repository.update(item.id, title = title, content = content, folder = folder, pinned = pinned, theme = theme, spacing = spacing)
        }
    }

    fun togglePinned(item: ArchiveItem) {
        viewModelScope.launch { repository.update(item.id, pinned = !item.pinned) }
    }

    fun toggleWatched(item: ArchiveItem) {
        val next = if (item.status == ArchiveStatus.DONE) ArchiveStatus.OPEN else ArchiveStatus.DONE
        viewModelScope.launch { repository.update(item.id, status = next) }
    }

    fun delete(item: ArchiveItem) {
        viewModelScope.launch { repository.delete(item.id) }
    }

    // --- generic lists (shopping + custom) --------------------------------

    fun createList(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { lists.createList(name) }
    }

    fun deleteList(list: ArchiveList) {
        viewModelScope.launch { lists.deleteList(list.name) }
    }

    fun addToShopping(title: String, quantity: Int?) {
        if (title.isBlank()) return
        viewModelScope.launch { lists.addItem("spesa", title, quantity) }
    }

    fun addListItem(list: ArchiveList, title: String, quantity: Int?) {
        if (title.isBlank()) return
        viewModelScope.launch { lists.addItem(list.name, title, quantity) }
    }

    fun toggleListItem(listName: String, item: ArchiveListItem) {
        val done = item.status != ListItemStatus.DONE
        viewModelScope.launch { lists.completeItem(listName, item.title, done) }
    }

    fun deleteListItem(listName: String, item: ArchiveListItem) {
        viewModelScope.launch { lists.removeItem(listName, item.title) }
    }
}
