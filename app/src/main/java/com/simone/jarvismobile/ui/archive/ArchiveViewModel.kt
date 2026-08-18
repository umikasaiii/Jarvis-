package com.simone.jarvismobile.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.archive.ArchiveRepository
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.archive.ArchiveStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the "Archivio" screen (spec §4): create/read/update/delete NOTE and
 * TO_WATCH items, plain text search. The same [ArchiveRepository] the Pro
 * Mode tools use, so a note created here is immediately visible to the AI
 * and vice versa — one store, not a UI-only copy of it.
 */
@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val repository: ArchiveRepository,
) : ViewModel() {

    private val allItems: StateFlow<List<ArchiveItem>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun setQuery(value: String) {
        query.value = value
    }

    fun createNote(title: String, content: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.create(ArchiveKind.NOTE, title, content) }
    }

    fun createWatchItem(title: String, watchType: String, link: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.create(ArchiveKind.TO_WATCH, title, watchType = watchType, link = link) }
    }

    fun update(item: ArchiveItem, title: String, content: String) {
        viewModelScope.launch { repository.update(item.id, title = title, content = content) }
    }

    fun toggleWatched(item: ArchiveItem) {
        val next = if (item.status == ArchiveStatus.DONE) ArchiveStatus.OPEN else ArchiveStatus.DONE
        viewModelScope.launch { repository.update(item.id, status = next) }
    }

    fun delete(item: ArchiveItem) {
        viewModelScope.launch { repository.delete(item.id) }
    }
}
