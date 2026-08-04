package com.simone.jarvismobile.ui.memory

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.memory.MemoryIndex
import com.simone.jarvismobile.memory.VaultRepository
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
) : ViewModel() {

    val status: StateFlow<MemoryIndex.Status> = memory.status

    private val _vaultName = MutableStateFlow<String?>(null)
    val vaultName: StateFlow<String?> = _vaultName.asStateFlow()

    init {
        refreshName()
    }

    fun onVaultPicked(uri: Uri) {
        viewModelScope.launch {
            vault.setVault(uri)
            refreshName()
            memory.rebuild()
        }
    }

    fun reindex() {
        viewModelScope.launch { memory.rebuild() }
    }

    fun disconnect() {
        viewModelScope.launch {
            vault.clearVault()
            memory.clear()
            _vaultName.value = null
        }
    }

    private fun refreshName() {
        viewModelScope.launch { _vaultName.value = vault.vaultName() }
    }
}
