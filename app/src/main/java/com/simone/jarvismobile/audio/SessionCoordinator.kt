package com.simone.jarvismobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.simone.jarvismobile.core.session.AudioKind
import com.simone.jarvismobile.core.session.Fase1Environment
import com.simone.jarvismobile.core.session.Fase1Flow
import com.simone.jarvismobile.core.session.PrepareOutcome
import com.simone.jarvismobile.core.session.RecordOutcome
import com.simone.jarvismobile.core.session.SpeakOutcome
import com.simone.jarvismobile.core.state.ConversationEvent
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.core.state.ConversationStateMachine
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns a single Phase-1 session and is the single source of truth the UI and the
 * foreground service observe. It implements the pure [Fase1Environment] with real
 * Android audio + TTS and runs the flow against the shared [ConversationStateMachine].
 *
 * Only one session runs at a time (guarded by [sessionMutex]). Audio buffers are
 * never persisted; logs are technical and redacted.
 */
@Singleton
class SessionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRouteManager: AudioRouteManager,
    private val audioCapture: AudioCapture,
    private val tts: TextToSpeechEngine,
    private val settings: SettingsRepository,
) : Fase1Environment {

    private val machine = ConversationStateMachine()
    val state: StateFlow<ConversationState> = machine.state

    val micLevel: StateFlow<Float> = audioCapture.micLevel
    val routeState: StateFlow<AudioRouteState> = audioRouteManager.routeState
    val ttsState: StateFlow<TtsState> = tts.state
    val selectedVoiceName: StateFlow<String?> = tts.selectedVoiceName

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val sessionMutex = Mutex()
    private var recordMs: Long = DEFAULT_RECORD_MS

    /** Runs one complete Phase-1 session. Safe to call repeatedly; ignores overlap. */
    suspend fun runSession() {
        if (sessionMutex.isLocked) {
            Log.i(TAG, "session_skip already_running")
            return
        }
        sessionMutex.withLock {
            _lastError.value = null
            recordMs = settings.recordSeconds.first() * 1_000L
            val end = Fase1Flow(machine, this).run()
            if (end is ConversationState.RecoverableError) _lastError.value = end.code
            Log.i(TAG, "session_end state=${end::class.simpleName}")
        }
    }

    fun cancel() {
        audioCapture.cancel()
        tts.stop()
        machine.dispatch(ConversationEvent.CancelRequested)
    }

    /** Diagnostics: record a fixed window only, to exercise the mic + level meter. */
    suspend fun testMicrophone(): CaptureResult {
        val result = audioCapture.capture(recordMs)
        if (result != CaptureResult.COMPLETED) _lastError.value = "mic_test_${result.name.lowercase()}"
        return result
    }

    /** Diagnostics: speak the fixed phrase to exercise offline TTS routing. */
    suspend fun testVoice(): Boolean {
        if (!tts.ensureReady()) { _lastError.value = "tts_unavailable"; return false }
        tts.speak(FIXED_REPLY)
        return true
    }

    /** Diagnostics: stop everything and return the machine to a usable state. */
    fun resetAudio() {
        audioCapture.cancel()
        tts.stop()
        audioRouteManager.endSession()
        machine.dispatch(ConversationEvent.Reset)
        _lastError.value = null
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // --- Fase1Environment (real Android side effects) --------------------

    override suspend fun prepareAudio(): PrepareOutcome {
        if (!hasRecordPermission()) return PrepareOutcome.PermissionDenied
        return try {
            val input = audioRouteManager.beginSession(preferBluetooth = true)
            val kind = when (input.kind) {
                AudioDeviceKind.AIRPODS -> AudioKind.AIRPODS
                AudioDeviceKind.BLUETOOTH_HEADSET -> AudioKind.BLUETOOTH
                else -> AudioKind.PHONE
            }
            val fellBack = kind == AudioKind.PHONE && routeState.value.bluetoothConnected
            PrepareOutcome.Ready(kind, fellBack)
        } catch (e: Exception) {
            _lastError.value = "audio_begin_failed"
            PrepareOutcome.Failure("audio_begin_failed")
        }
    }

    override suspend fun record(): RecordOutcome = when (audioCapture.capture(recordMs)) {
        CaptureResult.COMPLETED -> RecordOutcome.Completed
        CaptureResult.PERMISSION_DENIED -> RecordOutcome.Failure("permission")
        CaptureResult.FAILED -> RecordOutcome.Failure("record_failed")
    }

    override suspend fun ensureTtsReady(): Boolean = tts.ensureReady()

    override suspend fun speak(): SpeakOutcome = try {
        tts.speak(FIXED_REPLY)
        SpeakOutcome.Done
    } catch (e: Exception) {
        SpeakOutcome.Failure("tts_error")
    }

    override suspend fun endSession() {
        audioRouteManager.endSession()
    }

    companion object {
        const val DEFAULT_RECORD_MS = 3_000L
        const val FIXED_REPLY = "Sistema audio operativo. Sono pronto."
        private const val TAG = "JarvisSession"
    }
}
