package com.simone.jarvismobile.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.document.DocumentImportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the document archive screen (list + remove of imported documents). */
@HiltViewModel
class DocumentArchiveViewModel @Inject constructor(
    private val importer: DocumentImportManager,
) : ViewModel() {

    val documents: StateFlow<List<DocumentRecord>> =
        importer.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { importer.refresh() }
    }

    fun remove(id: String) = importer.remove(id)
    fun cancel(id: String) = importer.cancel(id)
}
