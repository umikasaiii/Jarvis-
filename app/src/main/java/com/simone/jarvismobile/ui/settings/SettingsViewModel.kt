package com.simone.jarvismobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.agenda.AgendaRepository
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
    private val agenda: AgendaRepository,
) : ViewModel() {

    val assistantName: StateFlow<String> = settings.assistantName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_NAME)

    val recordSeconds: StateFlow<Int> = settings.recordSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_RECORD_SECONDS)

    val useBluetooth: StateFlow<Boolean> = settings.useBluetooth
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val followUpEnabled: StateFlow<Boolean> = settings.followUpEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val responseNotifications: StateFlow<Boolean> = settings.responseNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val showResponsePreview: StateFlow<Boolean> = settings.showResponsePreview
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val reminderNotifications: StateFlow<Boolean> = settings.reminderNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val reminderMorningHour: StateFlow<Int> = settings.reminderMorningHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 8)

    fun setAssistantName(value: String) = viewModelScope.launch { settings.setAssistantName(value) }
    fun setRecordSeconds(value: Int) = viewModelScope.launch { settings.setRecordSeconds(value) }
    fun setUseBluetooth(value: Boolean) = viewModelScope.launch { settings.setUseBluetooth(value) }
    fun setFollowUpEnabled(value: Boolean) = viewModelScope.launch { settings.setFollowUpEnabled(value) }
    fun setResponseNotifications(value: Boolean) = viewModelScope.launch { settings.setResponseNotifications(value) }
    fun setShowResponsePreview(value: Boolean) = viewModelScope.launch { settings.setShowResponsePreview(value) }
    fun setReminderNotifications(value: Boolean) = viewModelScope.launch {
        settings.setReminderNotifications(value)
        agenda.reload()
    }
    fun setReminderMorningHour(value: Int) = viewModelScope.launch {
        settings.setReminderMorningHour(value)
        agenda.reload()
    }
    fun resetAudio() = coordinator.resetAudio()
    fun newConversation() = coordinator.newConversation()
}
