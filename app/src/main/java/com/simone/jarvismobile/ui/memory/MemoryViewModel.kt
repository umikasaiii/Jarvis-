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

    fun add(text: String, kind: MemoryKind) {
        viewModelScope.launch {
            _busy.value = true
            val saved = memory.remember(text, kind)
            _message.value = when {
                saved != null && kind == MemoryKind.TEMPORARY -> "Aggiunto alla memoria breve."
                saved != null -> "Ricordo salvato in Obsidian."
                else -> "Non salvato: controlla vault, testo e dati riservati."
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

    fun delete(id: String) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = if (memory.delete(id) != null) "Ricordo eliminato." else "Eliminazione non riuscita."
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
