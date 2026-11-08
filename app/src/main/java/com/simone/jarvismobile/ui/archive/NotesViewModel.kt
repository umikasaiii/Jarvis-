package com.simone.jarvismobile.ui.archive

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.archive.ArchiveRepository
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord
import com.simone.jarvismobile.core.notes.NoteEntry
import com.simone.jarvismobile.core.notes.NoteRef
import com.simone.jarvismobile.core.notes.NoteSource
import com.simone.jarvismobile.core.notes.NotesFeed
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.NoteBackgroundStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Presentation layer of the unified "Note" screen (BETA 3). It owns no data:
 * notes written in Archivio live in [ArchiveRepository] (Room) and the notes
 * the user keeps in Memoria live in [MemoryIndex] (`JARVIS/Memoria.md`). This
 * class merges them into read-only [NoteEntry] views ([NotesFeed]) and sends
 * every edit back to the store that owns the note — nothing is copied or
 * migrated, and conversation-only (TEMPORARY) memory is never listed.
 */
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val archive: ArchiveRepository,
    private val memory: MemoryIndex,
    val backgroundStore: NoteBackgroundStore,
) : ViewModel() {

    private val memoryRecords = MutableStateFlow<List<MemoryRecord>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _folder = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow("")
    private val _backgrounds = MutableStateFlow<List<String>>(emptyList())
    private val saveLock = Mutex()

    val query: StateFlow<String> = _query.asStateFlow()
    val selectedFolder: StateFlow<String?> = _folder.asStateFlow()
    val message: StateFlow<String> = _message.asStateFlow()
    val customBackgrounds: StateFlow<List<String>> = _backgrounds.asStateFlow()

    private val everything: StateFlow<List<NoteEntry>> =
        combine(archive.observeAll(), memoryRecords) { items, records -> NotesFeed.merge(items, records) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<String>> = everything
        .map { NotesFeed.folders(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val total: StateFlow<Int> = everything
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val hasUnfiled: StateFlow<Boolean> = everything
        .map { list -> list.any { it.folder.isBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val notes: StateFlow<List<NoteEntry>> =
        combine(everything, _query, _folder) { all, q, f -> NotesFeed.sorted(NotesFeed.filter(all, q, f)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshMemory()
        _backgrounds.value = backgroundStore.list()
    }

    fun setQuery(value: String) { _query.value = value }
    fun selectFolder(folder: String?) { _folder.value = folder }
    fun clearMessage() { _message.value = "" }

    /** Memoria can be edited from its own screen, so re-read it whenever Note is shown. */
    fun refreshMemory() {
        viewModelScope.launch {
            memoryRecords.value = runCatching { memory.listRecords() }.getOrDefault(memoryRecords.value)
        }
    }

    fun importBackground(uri: Uri) {
        viewModelScope.launch {
            val id = backgroundStore.import(uri)
            _message.value = if (id != null) "Sfondo importato." else "Importazione non riuscita: scegli un'immagine valida."
            _backgrounds.value = backgroundStore.list()
        }
    }

    fun deleteBackground(id: String) {
        backgroundStore.delete(id)
        _backgrounds.value = backgroundStore.list()
    }

    /**
     * Saves one note into its owning store. [ref] null = a new note, which is
     * always created in the Archive store. Serialised, so rapid edits cannot
     * interleave or create the same note twice.
     */
    suspend fun save(
        ref: NoteRef?,
        title: String,
        content: String,
        folder: String,
        pinned: Boolean,
        theme: String,
        spacing: String,
    ): NoteSaveResult = saveLock.withLock {
        runCatching {
            if (ref == null) {
                val created = archive.create(ArchiveKind.NOTE, title, content, folder = folder, pinned = pinned, theme = theme, spacing = spacing)
                created?.let { NoteSaveResult.Saved(NoteRef(NoteSource.ARCHIVE, it.id)) }
                    ?: NoteSaveResult.Failed("Impossibile creare la nota.")
            } else when (ref.source) {
                NoteSource.ARCHIVE -> {
                    val updated = archive.update(ref.id, title = title, content = content, folder = folder, pinned = pinned, theme = theme, spacing = spacing)
                    if (updated != null) NoteSaveResult.Saved(ref) else NoteSaveResult.Failed("La nota non esiste più o non si può scrivere.")
                }
                NoteSource.MEMORIA -> saveMemoryNote(ref, content, folder, theme, spacing)
            }
        }.getOrElse { NoteSaveResult.Failed("Errore di scrittura: ${it.javaClass.simpleName}.") }
    }

    private suspend fun saveMemoryNote(ref: NoteRef, content: String, folder: String, theme: String, spacing: String): NoteSaveResult {
        val current = memory.listRecords().firstOrNull { it.id == ref.id }
            ?: return NoteSaveResult.Failed("Il ricordo non esiste più.")
        if (content.isBlank()) return NoteSaveResult.Failed("Un ricordo non può essere vuoto: eliminalo se non serve più.")
        val kind = if (current.kind == MemoryKind.SENSITIVE) MemoryKind.SENSITIVE else MemoryKind.PERMANENT
        val updated = memory.update(ref.id, content, kind, theme, spacing)
            ?: return NoteSaveResult.Failed("Testo non accettato dalla memoria (niente dati riservati o credenziali).")
        if (updated.category != folder) memory.setCategory(ref.id, folder)
        refreshMemory()
        return NoteSaveResult.Saved(ref)
    }

    fun delete(ref: NoteRef) {
        viewModelScope.launch {
            saveLock.withLock {
                val ok = when (ref.source) {
                    NoteSource.ARCHIVE -> archive.delete(ref.id)
                    NoteSource.MEMORIA -> (memory.delete(ref.id) != null).also { refreshMemory() }
                }
                _message.value = if (ok) "Nota eliminata." else "Eliminazione non riuscita."
            }
        }
    }

    fun togglePin(entry: NoteEntry) {
        if (!entry.canPin) return
        viewModelScope.launch { archive.update(entry.ref.id, pinned = !entry.pinned) }
    }

    fun move(entry: NoteEntry, folder: String) {
        viewModelScope.launch {
            saveLock.withLock {
                when (entry.ref.source) {
                    NoteSource.ARCHIVE -> archive.update(entry.ref.id, folder = folder)
                    NoteSource.MEMORIA -> { memory.setCategory(entry.ref.id, folder); refreshMemory() }
                }
            }
        }
    }

    /** Re-files every note of [oldPath] (and subfolders) in both stores. Notes are never deleted. */
    fun renameFolder(oldPath: String, newPath: String) {
        val from = oldPath.trim(); val to = newPath.trim()
        if (from.isBlank() || to.isBlank() || from == to) return
        viewModelScope.launch {
            everything.value.filter { it.folder == from || it.folder.startsWith("$from/") }.forEach { n ->
                move(n, if (n.folder == from) to else to + n.folder.removePrefix(from))
            }
            if (_folder.value == from) _folder.value = to
        }
    }

    /** "Deleting" a folder only un-files its notes. */
    fun deleteFolder(path: String) {
        val target = path.trim()
        if (target.isBlank()) return
        viewModelScope.launch {
            everything.value.filter { it.folder == target || it.folder.startsWith("$target/") }.forEach { move(it, "") }
            if (_folder.value == target) _folder.value = null
        }
    }
}
