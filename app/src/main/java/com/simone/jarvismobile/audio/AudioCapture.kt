package com.simone.jarvismobile.audio

import com.simone.jarvismobile.core.voice.PcmCaptureMode
import com.simone.jarvismobile.core.voice.VoiceCaptureOutcome
import com.simone.jarvismobile.core.voice.VoiceCaptureSnapshot
import com.simone.jarvismobile.core.voice.VoicePcmFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Result of a fixed Phase-1 capture window. */
enum class CaptureResult { COMPLETED, FAILED, PERMISSION_DENIED }

/**
 * The single live-capture owner (Live Voice Phase 0.7). Historically: records a short,
 * fixed audio window for the diagnostics mic test / level meter — still its only production
 * consumer ([capture], via SessionCoordinator.testMicrophone). It now also exposes the
 * canonical bounded PCM frame stream ([frames]) and a continuous mode for the future VAD /
 * AEC / streaming-STT fan-out. PCM is never persisted. Production conversation STT and the
 * wake word still use Android SpeechRecognizer (which owns the mic itself): do NOT run any
 * capture here while a SpeechRecognizer session is active.
 */
interface AudioCapture {
    /** Normalized microphone level (0f..1f), updated while capturing. */
    val micLevel: StateFlow<Float>

    /** Technical detail of the last capture attempt (for diagnostics; redacted). */
    val lastDetail: StateFlow<String>

    /** Bounded real-time PCM frames (replay = 0, never StateFlow, never persisted). Nothing subscribes in production yet. */
    val frames: SharedFlow<VoicePcmFrame>

    /** Bounded technical observability (state, generation, frames read/dropped). No PCM content. */
    val captureSnapshot: StateFlow<VoiceCaptureSnapshot>

    /** Captures until cancelled ([cancel] or coroutine cancellation), emitting [frames]. Not used in production yet. */
    suspend fun captureContinuous(mode: PcmCaptureMode = PcmCaptureMode.STANDARD): VoiceCaptureOutcome

    /** Captures for [durationMs] then stops. Returns the outcome; never throws. */
    suspend fun capture(durationMs: Long): CaptureResult

    /** Requests cancellation of an in-progress capture. */
    fun cancel()
}
