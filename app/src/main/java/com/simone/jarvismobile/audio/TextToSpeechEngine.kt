package com.simone.jarvismobile.audio

import kotlinx.coroutines.flow.StateFlow

/** Whether speech is currently being produced. */
enum class TtsState { IDLE, SPEAKING, ERROR }

/**
 * Offline text-to-speech abstraction (docs/ARCHITECTURE.md §16). The first
 * implementation wraps Android [android.speech.tts.TextToSpeech] restricted to
 * voices that do not require the network. Later phases can swap in a sherpa-onnx
 * or Piper engine behind this same interface.
 */
interface TextToSpeechEngine {

    val state: StateFlow<TtsState>

    /** Name of the resolved offline voice (for diagnostics), or null if none. */
    val selectedVoiceName: StateFlow<String?>

    /** Technical detail of the last init/voice-setup attempt (for diagnostics). */
    val lastDetail: StateFlow<String>

    /** True once an offline Italian voice has been resolved and is ready. */
    suspend fun ensureReady(): Boolean

    /** Speaks [text]; suspends until playback finishes or is stopped/failed. */
    suspend fun speak(text: String)

    /** Immediately stops any current utterance. */
    fun stop()

    fun shutdown()
}
