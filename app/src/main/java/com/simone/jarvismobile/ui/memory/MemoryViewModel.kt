package com.simone.jarvismobile.ui.memory

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.VaultRepository
import com.simone.jarvismobile.memory.ConversationMemoryStore
import com.simone.jarvismobile.core.memory.MemoryKind
import com.simone.jarvismobile.core.memory.MemoryRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val vault: VaultRepository,
    private val memory: MemoryIndex,
    private val conversationMemory: ConversationMemoryStore,
) : ViewModel() {

    val status: StateFlow<MemoryIndex.Status> = memory.status

    private val _vaultName = MutableStateFlow<String?>(null)
    val vaultName: StateFlow<String?> = _vaultName.asStateFlow()

    val shortTerm = conversationMemory.snapshot

    private val _records = MutableStateFlow<List<MemoryRecord>>(emptyList())
    val records: StateFlow<List<MemoryRecord>> = _records.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshAll()
    }

    fun onVaultPicked(uri: Uri) {
        viewModelScope.launch {
            vault.setVault(uri)
            refreshName()
            memory.rebuild()
            refreshRecords()
        }
    }

    fun reindex() {
        viewModelScope.launch {
            _busy.value = true
            memory.rebuild()
            conversationMemory.ensureLoaded()
            refreshRecords()
            _message.value = "Sincronizzazione completata."
            _busy.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            vault.clearVault()
            memory.clear()
            _records.value = emptyList()
            _vaultName.value = null
        }
    }

    fun add(text: String, kind: MemoryKind, category: String = "") {
        viewModelScope.launch {
            _busy.value = true
            // Manual add: honour the picked category exactly; if none, leave it
            // uncategorised (no AI) so the "Classifica con l'AI" button stays the
            // explicit way to sort it.
            val saved = memory.remember(text, kind, category, autoCategorize = false)
            _message.value = when {
                saved != null && kind == MemoryKind.TEMPORARY -> "Aggiunto alla memoria breve."
                saved != null -> "Ricordo salvato in memoria."
                else -> "Non salvato: controlla il testo (niente dati riservati)."
            }
            refreshRecords()
            _busy.value = false
        }
    }

    fun update(id: String, text: String, kind: MemoryKind) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = if (memory.update(id, text, kind) != null) {
                "Ricordo aggiornato."
            } else {
                "Aggiornamento non riuscito."
            }
            refreshRecords()
            _busy.value = false
        }
    }

    /** Moves a record to a category picked by hand in the archive. */
    fun setCategory(id: String, category: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { memory.setCategory(id, category) }
            refreshRecords()
            _busy.value = false
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = if (memory.delete(id) != null) "Ricordo eliminato." else "Eliminazione non riuscita."
            refreshRecords()
            _busy.value = false
        }
    }

    /**
     * Renames a folder (§ richiesta esplicita dell'utente: "le cartelle non
     * sono modificabili") — a folder is just [MemoryRecord.category], so
     * "renaming" it means re-filing every record that carries [oldPath],
     * including anything nested one level under it ("oldPath/Child" →
     * "newPath/Child") since a subfolder's path is literally prefixed with
     * its parent's name.
     */
    fun renameCategory(oldPath: String, newPath: String) {
        val from = oldPath.trim()
        val to = newPath.trim()
        if (from.isBlank() || to.isBlank() || from == to) return
        viewModelScope.launch {
            _busy.value = true
            _records.value.filter { it.category == from || it.category.startsWith("$from/") }
                .forEach { rec ->
                    val newCategory = if (rec.category == from) to else to + rec.category.removePrefix(from)
                    runCatching { memory.setCategory(rec.id, newCategory) }
                }
            refreshRecords()
            _busy.value = false
        }
    }

    /**
     * Un-files every record in [path] (and anything nested under it) back to
     * "Senza categoria" — a non-destructive "delete a folder": the notes
     * themselves are never touched, only their category, matching the rest
     * of the app's reluctance to destroy user data on a folder-level action.
     */
    fun deleteCategory(path: String) {
        val target = path.trim()
        if (target.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            _records.value.filter { it.category == target || it.category.startsWith("$target/") }
                .forEach { rec -> runCatching { memory.setCategory(rec.id, "") } }
            refreshRecords()
            _busy.value = false
        }
    }

    /** Sorts uncategorised records into macro-categories with the on-device model. */
    fun reclassify() {
        viewModelScope.launch {
            _busy.value = true
            val changed = runCatching { memory.categorizeUncategorized() }.getOrDefault(0)
            _message.value = when {
                changed > 0 -> "Classificati $changed ricordi."
                else -> "Nessuna categoria assegnata: assicurati che un modello sia caricato."
            }
            refreshRecords()
            _busy.value = false
        }
    }

    fun clearTemporary() {
        viewModelScope.launch {
            conversationMemory.clear()
            _message.value = "Memoria breve eliminata."
        }
    }

    private fun refreshName() {
        viewModelScope.launch { _vaultName.value = vault.vaultName() }
    }

    private fun refreshAll() {
        viewModelScope.launch {
            _busy.value = true
            conversationMemory.ensureLoaded()
            _vaultName.value = vault.vaultName()
            if (vault.isConfigured()) memory.rebuild()
            refreshRecords()
            _busy.value = false
        }
    }

    private suspend fun refreshRecords() {
        _records.value = runCatching { memory.listRecords() }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }
}
