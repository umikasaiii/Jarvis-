package com.simone.jarvismobile.ui.diagnostics

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.AudioRouteState
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.TtsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snapshot of permission grants relevant to the audio loop. */
data class PermissionSnapshot(
    val microphone: Boolean,
    val notifications: Boolean,
    val bluetooth: Boolean,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    application: Application,
    private val coordinator: SessionCoordinator,
) : AndroidViewModel(application) {

    val routeState: StateFlow<AudioRouteState> = coordinator.routeState
    val ttsState: StateFlow<TtsState> = coordinator.ttsState
    val selectedVoiceName: StateFlow<String?> = coordinator.selectedVoiceName
    val micLevel: StateFlow<Float> = coordinator.micLevel
    val lastError: StateFlow<String?> = coordinator.lastError

    fun permissions(): PermissionSnapshot {
        val ctx = getApplication<Application>()
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val notif = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        return PermissionSnapshot(
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            notifications = notif,
            bluetooth = granted(Manifest.permission.BLUETOOTH_CONNECT),
        )
    }

    fun onTestMicrophone() = viewModelScope.launch { coordinator.testMicrophone() }
    fun onTestVoice() = viewModelScope.launch { coordinator.testVoice() }
    fun onResetAudio() = coordinator.resetAudio()
}
