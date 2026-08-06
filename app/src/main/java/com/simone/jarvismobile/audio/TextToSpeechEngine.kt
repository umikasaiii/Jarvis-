package com.simone.jarvismobile.audio

import kotlinx.coroutines.flow.StateFlow

/** Whether speech is currently being produced. */
enum class TtsState { IDLE, SPEAKING, ERROR }

/** One installed voice that Android reports as usable without a network. */
data class TtsVoiceOption(
    val name: String,
    val label: String,
    val languageTag: String,
)

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

    /** Installed, non-network voices available for explicit selection. */
    val availableVoices: StateFlow<List<TtsVoiceOption>>

    /** Technical detail of the last init/voice-setup attempt (for diagnostics). */
    val lastDetail: StateFlow<String>

    /** True once an offline Italian voice has been resolved and is ready. */
    suspend fun ensureReady(): Boolean

    /** Re-reads installed voices after the user installs/removes TTS data. */
    suspend fun refreshVoices(): List<TtsVoiceOption>

    /** Applies an installed voice plus safe rate/pitch bounds. Blank = automatic. */
    suspend fun configure(voiceName: String?, speechRate: Float, pitch: Float): Boolean

    /** Speaks [text]; suspends until playback finishes or is stopped/failed. */
    suspend fun speak(text: String)

    /** Immediately stops any current utterance. */
    fun stop()

    fun shutdown()
}
