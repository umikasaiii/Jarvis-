package com.simone.jarvismobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.simone.jarvismobile.audio.AudioRouteState
import com.simone.jarvismobile.audio.ListeningService
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.TtsState
import com.simone.jarvismobile.core.state.ConversationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin Android adapter for the Home screen. It starts the foreground
 * [ListeningService] (which owns the mic and drives the session) and exposes the
 * shared [SessionCoordinator] state for the UI. All conversation logic lives in
 * the pure-Kotlin core (docs/ARCHITECTURE.md §5/§6).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val coordinator: SessionCoordinator,
) : AndroidViewModel(application) {

    val state: StateFlow<ConversationState> = coordinator.state
    val routeState: StateFlow<AudioRouteState> = coordinator.routeState
    val ttsState: StateFlow<TtsState> = coordinator.ttsState
    val micLevel: StateFlow<Float> = coordinator.micLevel

    /** Phase-1 entry point: begins an explicit, user-initiated session. */
    fun onTalkPressed() {
        // Started from a foreground Activity, so starting the mic FGS is allowed.
        ListeningService.start(getApplication())
    }

    fun onCancel() {
        coordinator.cancel()
        ListeningService.stop(getApplication())
    }
}
