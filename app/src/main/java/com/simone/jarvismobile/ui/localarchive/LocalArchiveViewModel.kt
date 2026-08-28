package com.simone.jarvismobile.ui.localarchive

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.archive.ArchiveRepository
import com.simone.jarvismobile.core.archive.ArchiveItem
import com.simone.jarvismobile.core.archive.ArchiveKind
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentStatus
import com.simone.jarvismobile.document.DocumentImportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs "Archivio locale" (§ richiesta esplicita dell'utente): a single place
 * to browse, search, open and share/export the files and notes JARVIS already
 * stores — not a new store of its own. Files come straight from
 * [DocumentImportManager] (the same import pipeline the chat's "+" and the
 * Archivio documenti screen use); notes come straight from [ArchiveRepository]
 * (the same store the Archivio screen's Appunti tab uses). Both are already
 * real content in the nightly backup (§ BackupRepository — `documents` moved
 * off the manifest-only heavy-dirs list for exactly this feature) and already
 * reachable to the AI via `search_documents`/`search_archive`.
 */
@HiltViewModel
class LocalArchiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentManager: DocumentImportManager,
    private val archiveRepository: ArchiveRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query

    // Subscribed once each and reused below, rather than re-subscribing per
    // derived flow — same pattern ArchiveViewModel already uses for the same
    // reason (each Room Flow collection re-runs its query on every emission).
    private val readyDocuments: StateFlow<List<DocumentRecord>> = documentManager.documents
        .map { docs -> docs.filter { it.status == DocumentStatus.READY } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allNotes: StateFlow<List<ArchiveItem>> = archiveRepository.observeAll()
        .map { items -> items.filter { it.kind == ArchiveKind.NOTE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val documents: StateFlow<List<DocumentRecord>> = combine(readyDocuments, query) { docs, q ->
        docs.filter { q.isBlank() || it.displayName.contains(q, ignoreCase = true) }
            .sortedByDescending { it.importedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<ArchiveItem>> = combine(allNotes, query) { items, q ->
        items.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ready files + notes, for the dashboard tile's count — unfiltered by [query]. */
    val itemCount: StateFlow<Int> = combine(readyDocuments, allNotes) { docs, items ->
        docs.size + items.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setQuery(value: String) {
        query.value = value
    }

    /**
     * A `content://` URI for [record]'s app-private file, safe to hand to
     * another app (viewer, share sheet) — never a raw `file://` path, which
     * Android refuses to expose outside the app. Null when the file is
     * missing on disk (should not happen for a READY record, but a manual
     * edit outside the app is not this screen's problem to crash over).
     */
    fun shareUri(record: DocumentRecord): Uri? = runCatching {
        val file = documentManager.localFile(record)
        if (!file.exists()) return null
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
