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

    /** Returns false only when overlay permission is missing, so the caller can prompt for it. */
    fun toggle(onMissingPermission: () -> Unit) {
        viewModelScope.launch {
            if (state.value.active) {
                manager.stop()
            } else if (manager.start() == DrivingStartResult.MissingOverlayPermission) {
                onMissingPermission()
            }
        }
    }
}
