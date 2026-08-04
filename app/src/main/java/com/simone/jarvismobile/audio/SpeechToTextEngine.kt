package com.simone.jarvismobile.audio

import kotlinx.coroutines.flow.StateFlow

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

    /** True if offline recognition can run on this device. */
    fun isAvailable(): Boolean

    /** Listens on the microphone and returns the final result. Never throws. */
    suspend fun transcribe(languageTag: String = "it-IT"): SttResult

    /** Cancels an in-progress recognition. */
    fun cancel()
}
