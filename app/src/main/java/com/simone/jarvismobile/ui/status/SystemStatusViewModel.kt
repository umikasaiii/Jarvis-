package com.simone.jarvismobile.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.agenda.AgendaRepository
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.backup.BackupRepository
import com.simone.jarvismobile.backup.BackupState
import com.simone.jarvismobile.core.agenda.AgendaEntry
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.memory.MemoryIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the "Stato Sistema" screen (§ opened from the dashboard's Sistema
 * tile, which used to open the Models screen instead — a general status
 * overview never actually existed). Every field here comes from a repository
 * some other screen already reads; nothing is invented for this one, so
 * there is nothing to keep in sync with reality by hand.
 */
@HiltViewModel
class SystemStatusViewModel @Inject constructor(
    coordinator: SessionCoordinator,
    agenda: AgendaRepository,
    memory: MemoryIndex,
    backup: BackupRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val llmLoadState: StateFlow<LlmLoadState> = coordinator.llmLoadState
    val loadedModelName: StateFlow<String?> = coordinator.loadedModelName

    val memoryStatus: StateFlow<MemoryIndex.Status> = memory.status

    val agendaEntries: StateFlow<List<AgendaEntry>> = agenda.entries

    val backupState: StateFlow<BackupState> = backup.state

    val backupCloudEnabled: StateFlow<Boolean> = settings.backupCloudEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val backupCloudProvider: StateFlow<String> = settings.backupCloudProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val proactiveEnabled: StateFlow<Boolean> = settings.proactiveEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val automationServiceEnabled: StateFlow<Boolean> = settings.automationServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val wakeWordEnabled: StateFlow<Boolean> = settings.wakeWordEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
