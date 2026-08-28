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
import com.simone.jarvismobile.memory.MemoryIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

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
     */
    fun importFromPhone(uris: List<Uri>) {
        uris.forEach { documentManager.import(it, saveToVault = false) }
    }

    /** Removes an imported file from Archivio entirely (the pipeline record, not just the UI row). */
    fun removeDocument(record: DocumentRecord) {
        documentManager.remove(record.id)
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

    fun createNote(title: String, content: String, folder: String = "", pinned: Boolean = false) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.create(ArchiveKind.NOTE, title, content, folder = folder, pinned = pinned) }
    }

    fun createWatchItem(title: String, watchType: String, link: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.create(ArchiveKind.TO_WATCH, title, watchType = watchType, link = link) }
    }

    fun updateNote(item: ArchiveItem, title: String, content: String, folder: String, pinned: Boolean) {
        viewModelScope.launch {
            repository.update(item.id, title = title, content = content, folder = folder, pinned = pinned)
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
