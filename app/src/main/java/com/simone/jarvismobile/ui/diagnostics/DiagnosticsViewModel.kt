package com.simone.jarvismobile.ui.diagnostics

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simone.jarvismobile.audio.AudioRouteState
import com.simone.jarvismobile.audio.CaptureResult
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.audio.SttResult
import com.simone.jarvismobile.audio.TtsState
import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.core.driving.DrivingNavigationMode
import com.simone.jarvismobile.core.navigation.GpxParser
import com.simone.jarvismobile.core.navigation.GpxReplayRoute
import com.simone.jarvismobile.core.tts.SupertonicQuality
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.navigation.debug.DebugGpsSimulator
import com.simone.jarvismobile.tts.AudioFocusGate
import com.simone.jarvismobile.tts.PcmPlayer
import com.simone.jarvismobile.tts.SupertonicTtsEngine
import com.simone.jarvismobile.tts.TtsLoadResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val settings: SettingsRepository,
    private val supertonic: SupertonicTtsEngine,
    private val pcmPlayer: PcmPlayer,
    private val audioFocus: AudioFocusGate,
) : AndroidViewModel(application) {

    /**
     * Developer-only selector between the shipped Google-Maps overlay and the
     * new in-app navigation while it's being built (spec §1/§21). Defaults to
     * [DrivingNavigationMode.EXTERNAL_MAPS_OVERLAY].
     */
    val drivingNavigationMode: StateFlow<DrivingNavigationMode> = settings.drivingNavigationMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DrivingNavigationMode.EXTERNAL_MAPS_OVERLAY)

    fun setDrivingNavigationMode(mode: DrivingNavigationMode) {
        viewModelScope.launch { settings.setDrivingNavigationMode(mode) }
    }

    private val _gpxStatus = MutableStateFlow("")
    val gpxStatus: StateFlow<String> = _gpxStatus.asStateFlow()

    /**
     * Loads a GPX file picked via SAF for debug route replay (spec §28): parsed
     * once by the pure `:core` [GpxParser], then handed to [DebugGpsSimulator],
     * which [com.simone.jarvismobile.navigation.NavigationLocationProvider]
     * substitutes for real GNSS while the simulator is on. A no-op in release
     * ([DebugGpsSimulator.loadGpx] itself already refuses outside debug).
     */
    fun loadGpxDebugRoute(uri: android.net.Uri) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            val text = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                _gpxStatus.value = "Impossibile leggere il file GPX."
                return@launch
            }
            val points = GpxParser.parse(text)
            if (points.size < 2) {
                _gpxStatus.value = "GPX non valido o senza punti sufficienti."
                return@launch
            }
            DebugGpsSimulator.loadGpx(GpxReplayRoute(points))
            _gpxStatus.value = "Caricato: ${points.size} punti · ${points.last().elapsedMs / 1000} s."
        }
    }

    fun clearGpxDebugRoute() {
        DebugGpsSimulator.loadGpx(null)
        _gpxStatus.value = ""
    }

    val routeState: StateFlow<AudioRouteState> = coordinator.routeState
    val ttsState: StateFlow<TtsState> = coordinator.ttsState
    val selectedVoiceName: StateFlow<String?> = coordinator.selectedVoiceName
    val micLevel: StateFlow<Float> = coordinator.micLevel
    val lastError: StateFlow<String?> = coordinator.lastError

    private val _micStatus = MutableStateFlow("")
    val micStatus: StateFlow<String> = _micStatus.asStateFlow()

    private val _voiceStatus = MutableStateFlow("")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _sttStatus = MutableStateFlow("")
    val sttStatus: StateFlow<String> = _sttStatus.asStateFlow()
    val sttPartial: StateFlow<String> = coordinator.partialTranscript

    fun sttAvailable(): Boolean = coordinator.sttAvailable()

    fun hasMicPermission(): Boolean = coordinator.hasRecordPermission()

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

    /** Runs the fixed-window mic test. Caller must ensure permission first. */
    fun runMicTest() {
        if (_testing.value) return
        _testing.value = true
        _micStatus.value = "Registrazione in corso…"
        viewModelScope.launch {
            _micStatus.value = when (coordinator.testMicrophone()) {
                CaptureResult.COMPLETED -> "OK · microfono acquisito"
                CaptureResult.PERMISSION_DENIED -> "Permesso microfono negato"
                CaptureResult.FAILED -> "Errore · registrazione non riuscita"
            }
            _testing.value = false
        }
    }

    fun runVoiceTest() {
        if (_testing.value) return
        _testing.value = true
        _voiceStatus.value = "Sintesi in corso…"
        viewModelScope.launch {
            _voiceStatus.value = if (coordinator.testVoice()) {
                "OK · voce riprodotta"
            } else {
                "Voce italiana offline non disponibile — installala in Impostazioni Android › TTS"
            }
            _testing.value = false
        }
    }

    fun runSttTest() {
        if (_testing.value) return
        _testing.value = true
        _sttStatus.value = "In ascolto… parla ora"
        viewModelScope.launch {
            _sttStatus.value = when (val r = coordinator.testStt()) {
                is SttResult.Text -> "Riconosciuto: \"${r.text}\""
                SttResult.NoSpeech -> "Nessun parlato riconosciuto"
                is SttResult.Unavailable -> "Non disponibile: ${r.reason}"
                is SttResult.Failure -> "Errore: ${r.code}"
            }
            _testing.value = false
        }
    }

    fun onResetAudio() {
        coordinator.resetAudio()
        _micStatus.value = ""
        _voiceStatus.value = ""
        _sttStatus.value = ""
    }

    // --- Supertonic debug panel (BuildConfig.DEBUG only, see DiagnosticsScreen) ---
    // Deliberately bypasses NeuralTtsRepository/SettingsRepository: this must
    // never perturb the user's actual selected TTS engine or persisted voice
    // choice, only exercise SupertonicTtsEngine directly for an A/B listen.
    // SupertonicTtsEngine IS a Hilt @Singleton, though — the same session real
    // replies use — so the chosen profile is restored to the real default right
    // after playback instead of silently leaking into the next real reply.

    private val _supertonicBusy = MutableStateFlow(false)
    val supertonicBusy: StateFlow<Boolean> = _supertonicBusy.asStateFlow()

    private val _supertonicStatus = MutableStateFlow("")
    val supertonicStatus: StateFlow<String> = _supertonicStatus.asStateFlow()

    /** Synthesises and plays [SUPERTONIC_SAMPLE] at [profile], for a live A/B comparison. */
    fun runSupertonicProfile(profile: SupertonicQuality) {
        if (!BuildConfig.DEBUG || _supertonicBusy.value) return
        _supertonicBusy.value = true
        _supertonicStatus.value = "Sintesi $profile in corso…"
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            if (!supertonic.isLoaded) {
                val result = supertonic.load(java.io.File(""), null, null)
                if (result is TtsLoadResult.Failed) {
                    _supertonicStatus.value = "Supertonic non disponibile: ${result.reason}"
                    _supertonicBusy.value = false
                    return@launch
                }
            }
            supertonic.setQualityProfile(profile)
            val pcm = supertonic.synthesize(SUPERTONIC_SAMPLE, voice = "supertonic", speed = 1.0f)
            if (pcm == null || pcm.isEmpty()) {
                _supertonicStatus.value = "Sintesi $profile non riuscita."
                _supertonicBusy.value = false
                return@launch
            }
            val elapsed = System.currentTimeMillis() - startedAt
            audioFocus.acquire { pcmPlayer.stop() }
            try {
                pcmPlayer.start(supertonic.sampleRate)
                pcmPlayer.write(pcm)
                pcmPlayer.drain()
            } finally {
                audioFocus.release()
            }
            val seconds = pcm.size.toFloat() / supertonic.sampleRate
            _supertonicStatus.value =
                "$profile (passi=${profile.numSteps}): %.2fs audio in %dms".format(seconds, elapsed)
            supertonic.setQualityProfile(SupertonicQuality.BALANCED)
            _supertonicBusy.value = false
        }
    }

    private companion object {
        const val SUPERTONIC_SAMPLE = "Ciao. Sono JARVIS. Il nuovo sistema vocale locale è attivo."
    }
}
