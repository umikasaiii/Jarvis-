package com.simone.jarvismobile.ui.driving

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.core.driving.DrivingModeState
import com.simone.jarvismobile.driving.DrivingModeManager
import com.simone.jarvismobile.driving.DrivingStartResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Thin Home-screen wrapper over [DrivingModeManager] — no state of its own. */
@HiltViewModel
class DrivingModeEntryViewModel @Inject constructor(
    private val manager: DrivingModeManager,
) : ViewModel() {

    val state: StateFlow<DrivingModeState> =
        manager.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DrivingModeState())

    fun hasOverlayPermission(): Boolean = manager.hasOverlayPermission()

    fun overlayPermissionIntent() = manager.overlayPermissionIntent()

    fun notificationAccessIntent() = manager.notificationAccessIntent()

    /**
     * [onMissingOverlayPermission] blocks the start entirely — there is no overlay
     * without it. [onMissingNotificationAccess] fires *after* a successful start:
     * Modalità Guida still works (map, wake word, navigation) without it, it just
     * means messages and Spotify will show nothing until it is granted.
     */
    fun toggle(onMissingOverlayPermission: () -> Unit, onMissingNotificationAccess: () -> Unit) {
        viewModelScope.launch {
            if (state.value.active) {
                manager.stop()
                return@launch
            }
            when (manager.start()) {
                DrivingStartResult.MissingOverlayPermission -> onMissingOverlayPermission()
                DrivingStartResult.Started -> if (!manager.hasNotificationAccess()) onMissingNotificationAccess()
            }
        }
    }
}
