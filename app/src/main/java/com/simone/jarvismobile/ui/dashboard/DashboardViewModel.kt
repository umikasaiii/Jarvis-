package com.simone.jarvismobile.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.memory.MemoryIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val coordinator: SessionCoordinator,
    memory: MemoryIndex,
    settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<ConversationState> = coordinator.state
    val llmLoadState: StateFlow<LlmLoadState> = coordinator.llmLoadState
    val loadedModelName: StateFlow<String?> = coordinator.loadedModelName
    val memoryStatus: StateFlow<MemoryIndex.Status> = memory.status

    val assistantName: StateFlow<String> = settings.assistantName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_NAME)

    init {
        // Ensure the model auto-loads and the vault index is built even if the
        // user opens the app straight onto the dashboard tab.
        viewModelScope.launch { coordinator.ensureModelReady() }
        viewModelScope.launch { coordinator.ensureMemoryReady() }
    }

    fun hasRecordPermission(): Boolean = coordinator.hasRecordPermission()
    fun onTalkPressed() { viewModelScope.launch { coordinator.runSession() } }
    fun onCancel() = coordinator.cancel()
}
