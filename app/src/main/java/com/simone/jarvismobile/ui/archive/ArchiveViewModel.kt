package com.simone.jarvismobile.ui.archive

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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the "Tutto" tab: a note, a to-watch item, or a list item, tagged with its own kind. */
sealed interface ArchiveAllRow {
    val updatedAt: Long
    data class Note(val item: ArchiveItem) : ArchiveAllRow {
        override val updatedAt get() = item.updatedAt
    }
    data class ListEntry(val listName: String, val item: ArchiveListItem) : ArchiveAllRow {
        override val updatedAt get() = item.updatedAt
    }
}

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
    private val repository: ArchiveRepository,
    private val lists: ArchiveListRepository,
) : ViewModel() {

    private val allItems: StateFlow<List<ArchiveItem>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** Notes + to-watch + every list item, merged and filtered by [query] — the "Tutto" tab. */
    val allRows: StateFlow<List<ArchiveAllRow>> = combine(allItems, allLists, allListItems, query) { notes, ls, its, q ->
        val nameFor = ls.associateBy { it.id }
        val noteRows = notes
            .filter { q.isBlank() || it.title.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true) }
            .map { ArchiveAllRow.Note(it) }
        val listRows = its
            .filter { q.isBlank() || it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }
            .map { ArchiveAllRow.ListEntry(nameFor[it.listId]?.name ?: "", it) }
        (noteRows + listRows).sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
