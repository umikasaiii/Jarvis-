package com.simone.jarvismobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.TtsVoiceOption
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

    val ttsVoiceName: StateFlow<String> = settings.ttsVoiceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val selectedVoiceName: StateFlow<String?> = coordinator.selectedVoiceName
    val availableVoices: StateFlow<List<TtsVoiceOption>> = coordinator.availableVoices

    val ttsSpeechRate: StateFlow<Float> = settings.ttsSpeechRate
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_TTS_RATE,
        )

    val ttsPitch: StateFlow<Float> = settings.ttsPitch
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_TTS_PITCH,
        )

    val speakBackgroundResponses: StateFlow<Boolean> = settings.speakBackgroundResponses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch { coordinator.refreshVoices() }
    }

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
    fun refreshVoices() = viewModelScope.launch { coordinator.refreshVoices() }
    fun setTtsVoice(name: String) = viewModelScope.launch {
        settings.setTtsVoiceName(name)
        coordinator.configureVoice(name.ifBlank { null }, ttsSpeechRate.value, ttsPitch.value)
    }
    fun setTtsSpeechRate(value: Float) = viewModelScope.launch {
        settings.setTtsSpeechRate(value)
        coordinator.configureVoice(ttsVoiceName.value.ifBlank { null }, value, ttsPitch.value)
    }
    fun setTtsPitch(value: Float) = viewModelScope.launch {
        settings.setTtsPitch(value)
        coordinator.configureVoice(ttsVoiceName.value.ifBlank { null }, ttsSpeechRate.value, value)
    }
    fun setSpeakBackgroundResponses(value: Boolean) = viewModelScope.launch {
        settings.setSpeakBackgroundResponses(value)
    }
    fun resetAudio() = coordinator.resetAudio()
    fun newConversation() = coordinator.newConversation()
}
