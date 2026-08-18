package com.simone.jarvismobile.audio

import android.util.Log
import com.simone.jarvismobile.core.state.ConversationState
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the wake word — **only while the app is visibly in front of the user**.
 *
 * That is true in two situations, each its own explicit, user-controlled flag:
 * [setForegroundActive] (the home screen, ON_RESUME/ON_PAUSE) and
 * [setDrivingModeActive] (Modalità Guida, itself a foreground-service-backed,
 * persistently-notified, opt-in overlay — not a hidden background listener; see
 * `docs/PRIVACY.md`). The loop runs while *either* is true, listens for the
 * configured word (default: the assistant's name) whenever the assistant is
 * resting, and on a hit starts the normal listening session. It never runs with
 * both flags false and never holds the microphone while a session is active —
 * the moment the conversation leaves a resting state the in-flight wake listen
 * is cancelled, and the wake engine uses its own recognizer so it can never
 * abort that session.
 */
@Singleton
class WakeWordController @Inject constructor(
    private val engine: WakeWordEngine,
    private val coordinator: SessionCoordinator,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob())

    private val homeForeground = MutableStateFlow(false)
    private val drivingModeActive = MutableStateFlow(false)
    private val anyForeground: Boolean get() = homeForeground.value || drivingModeActive.value

    private val _listeningForWake = MutableStateFlow(false)
    /** True while actively listening for the wake word, for the orb hint. */
    val listeningForWake: StateFlow<Boolean> = _listeningForWake.asStateFlow()

    private var loop: Job? = null

    /** Called by the home screen: true on ON_RESUME, false on ON_PAUSE/dispose. */
    fun setForegroundActive(active: Boolean) {
        homeForeground.value = active
        if (anyForeground) start() else stop()
    }

    /** Called by Modalità Guida while its visible overlay session is running. */
    fun setDrivingModeActive(active: Boolean) {
        drivingModeActive.value = active
        if (anyForeground) start() else stop()
    }

    private fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            // Abort a wake listen the instant a session (manual or wake) begins.
            launch {
                coordinator.state.collect { st ->
                    if (st != ConversationState.Idle) engine.cancel()
                }
            }
            while (isActive) {
                val enabled = runCatching { settings.wakeWordEnabled.first() }.getOrDefault(false)
                val ready = enabled &&
                    anyForeground &&
                    engine.isAvailable() &&
                    coordinator.hasRecordPermission() &&
                    coordinator.state.value == ConversationState.Idle
                if (!ready) {
                    _listeningForWake.value = false
                    delay(IDLE_POLL_MS)
                    continue
                }
                val word = runCatching { settings.wakeWord.first() }.getOrDefault("")
                _listeningForWake.value = true
                val heard = engine.awaitWakeWord(word)
                _listeningForWake.value = false
                if (heard && anyForeground && coordinator.state.value == ConversationState.Idle) {
                    Log.i(TAG, "wake_word_detected")
                    // Let the wake recognizer fully release the shared recognition
                    // service before the session grabs it, or the session's STT can
                    // hit ERROR_RECOGNIZER_BUSY and the orb flashes an error.
                    engine.cancel()
                    delay(RELEASE_MS)
                    // startSession(), not runSession() directly: only startSession()
                    // registers the turn as sessionJob, which is what makes it
                    // reliably cancellable by a later orb tap. A direct runSession()
                    // call here used to leave a wake-triggered session untracked, so
                    // stopping it could only ever cancel the recognizer (which
                    // delivers no callback) and the orb stayed stuck until the 20s
                    // STT timeout.
                    coordinator.startSession()
                }
                delay(GAP_MS)
            }
        }
    }

    private fun stop() {
        runCatching { engine.cancel() }
        loop?.cancel()
        loop = null
        _listeningForWake.value = false
    }

    private companion object {
        const val TAG = "JarvisWake"
        const val IDLE_POLL_MS = 500L
        const val GAP_MS = 250L
        /** Grace for the shared recognizer to free up after a wake hit. */
        const val RELEASE_MS = 350L
    }
}
