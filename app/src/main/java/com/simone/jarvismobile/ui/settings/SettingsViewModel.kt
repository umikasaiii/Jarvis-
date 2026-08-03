package com.simone.jarvismobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val coordinator: SessionCoordinator,
) : ViewModel() {

    val assistantName: StateFlow<String> = settings.assistantName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_NAME)

    val recordSeconds: StateFlow<Int> = settings.recordSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_RECORD_SECONDS)

    val useBluetooth: StateFlow<Boolean> = settings.useBluetooth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setAssistantName(value: String) = viewModelScope.launch { settings.setAssistantName(value) }
    fun setRecordSeconds(value: Int) = viewModelScope.launch { settings.setRecordSeconds(value) }
    fun setUseBluetooth(value: Boolean) = viewModelScope.launch { settings.setUseBluetooth(value) }
    fun resetAudio() = coordinator.resetAudio()
}
