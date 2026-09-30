package com.simone.jarvismobile.audio

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Outcome of one offline recognition attempt. */
sealed interface SttResult {
    data class Text(val text: String) : SttResult
    /** Listened but heard no intelligible speech. */
    data object NoSpeech : SttResult
    /** On-device recognition isn't available on this device. */
    data class Unavailable(val reason: String) : SttResult
    /** Technical failure (code is redacted / non-personal). */
    data class Failure(val code: String) : SttResult
}

/**
 * Offline speech-to-text (docs/ARCHITECTURE.md §5). Phase-2 ships
 * [AndroidOnDeviceSpeechEngine] (system on-device recognizer, no model import);
 * a sherpa-onnx engine can be substituted behind this interface later.
 */
interface SpeechToTextEngine {
    /** Live partial transcript while listening (may be empty). */
    val partial: StateFlow<String>

    /**
     * Live Voice Phase 0.3 — real user-speech-boundary events
     * ([SttSpeechEvent]) for the current/most recent [transcribe]
     * invocation. `replay = 0`: a subscriber must already be collecting
     * before calling [transcribe] to reliably observe a fast STARTED —
     * exactly the same subscription-confirmed-before-emission-possible
     * discipline [com.simone.jarvismobile.audio.TextToSpeechEngine.playbackStartEvents]
     * already established in Phase 0.2. Never used as a source of truth by
     * this interface's own implementations or by anything downstream.
     */
    val speechEvents: SharedFlow<SttSpeechEvent>

    /** True if offline recognition can run on this device. */
    fun isAvailable(): Boolean

    /**
     * Listens on the microphone and returns the final result. Never throws.
     *
     * [invocationId] identifies this call for [speechEvents] — defaults to
     * a freshly generated id so every existing caller that never observes
     * [speechEvents] (e.g. [RecognizerWakeWordEngine]) remains
     * source-compatible, unchanged.
     */
    suspend fun transcribe(languageTag: String = "it-IT", invocationId: String = UUID.randomUUID().toString()): SttResult

    /** Cancels an in-progress recognition. */
    fun cancel()
}
