package com.simone.jarvismobile.tts

import java.io.File

/** The three files a neural voice can be made of. Only MODEL is always required. */
enum class TtsAssetKind(val label: String, val extensions: List<String>) {
    MODEL("Modello", listOf("onnx")),
    VOICES("Voci", listOf("bin", "npz")),
    VOCABULARY("Vocabolario", listOf("txt", "json")),
}

/** What a loaded engine can tell the UI about itself. */
data class NeuralTtsInfo(
    val engineId: String,
    val voices: List<String>,
    val sampleRate: Int,
    /** Where the phoneme table came from — the imported file or the built-in one. */
    val vocabularySource: String,
    /** Free-form technical note for the Settings screen. */
    val detail: String,
)

sealed interface TtsLoadResult {
    data class Ok(val info: NeuralTtsInfo) : TtsLoadResult
    data class Failed(val reason: String) : TtsLoadResult
}

/**
 * A local neural text-to-speech engine.
 *
 * Kokoro is the first implementation, but nothing above this interface knows
 * that: the engine owns its own file layout, its own tokenisation and its own
 * tensor names, and hands back plain mono PCM. Adding Piper or a VITS export
 * means adding a class here and an entry in [NeuralTtsEngines], not touching the
 * repository, the player or the UI.
 *
 * Everything runs on the device. No implementation may open a socket.
 */
interface NeuralTtsEngine {

    /** Stable id stored in preferences. */
    val id: String

    /** Shown in Settings. */
    val label: String

    /** Which of the three asset slots this engine actually uses. */
    val requiredAssets: Set<TtsAssetKind>

    /** Output sample rate in Hz; only meaningful once loaded. */
    val sampleRate: Int

    val isLoaded: Boolean

    /**
     * Builds the inference session. Called lazily, off the main thread, and only
     * once per set of files — the session is then kept alive so consecutive
     * replies do not pay the load cost again.
     */
    suspend fun load(model: File, voices: File?, vocabulary: File?): TtsLoadResult

    /** Voices the loaded pack contains. Empty before [load]. */
    fun voices(): List<String>

    /**
     * Synthesises one chunk — a sentence, not a whole reply — into mono float
     * PCM in [-1, 1]. Returns null when the chunk produced nothing usable.
     */
    suspend fun synthesize(text: String, voice: String, speed: Float): FloatArray?

    /** Frees the session and its memory. */
    fun release()
}

/** The engines this build knows about, in the order Settings offers them. */
object NeuralTtsEngines {
    const val KOKORO = "kokoro"
    const val NONE = ""
}
