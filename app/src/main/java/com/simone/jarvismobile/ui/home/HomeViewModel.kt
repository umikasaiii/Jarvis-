package com.simone.jarvismobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.AudioRouteState
import com.simone.jarvismobile.audio.ListeningService
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.TtsState
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home adapter. On tap it starts the foreground [ListeningService] (for the
 * ongoing microphone notification) and then runs the Phase-1 session in
 * [viewModelScope] — the app is in the foreground at that moment, so audio
 * capture is always permitted and the state visibly advances immediately.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val coordinator: SessionCoordinator,
    settings: SettingsRepository,
) : AndroidViewModel(application) {

    val state: StateFlow<ConversationState> = coordinator.state
    val routeState: StateFlow<AudioRouteState> = coordinator.routeState
    val ttsState: StateFlow<TtsState> = coordinator.ttsState
    val micLevel: StateFlow<Float> = coordinator.micLevel
    val lastError: StateFlow<String?> = coordinator.lastError

    val assistantName: StateFlow<String> = settings.assistantName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.DEFAULT_NAME)

    fun hasRecordPermission(): Boolean = coordinator.hasRecordPermission()

    /** Called once the user has granted (or already had) the mic permission. */
    fun onTalkPressed() {
        ListeningService.start(getApplication())
        viewModelScope.launch {
            coordinator.runSession()
            ListeningService.stop(getApplication())
        }
    }

    fun onCancel() {
        coordinator.cancel()
        ListeningService.stop(getApplication())
    }
}
