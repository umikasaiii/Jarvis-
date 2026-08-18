package com.simone.jarvismobile.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.simone.jarvismobile.BuildConfig
import com.simone.jarvismobile.core.tts.SupertonicQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supertonic 3 (INT8) running under sherpa-onnx 1.13.5 (`OfflineTts`).
 *
 * Bundled in the APK rather than user-imported — see [SupertonicAssetProvisioner]
 * for why that makes this engine, alone among [NeuralTtsEngine] implementations,
 * declare [requiredAssets] empty: there is nothing for the user to supply.
 * [load] ignores the file arguments the [NeuralTtsEngine] contract passes it
 * (they exist for Kokoro/Piper's user-import flow, not this one) and resolves
 * its seven files itself, from the extracted bundle.
 *
 * Kept warm exactly like Kokoro/Piper: [OfflineTts] is built once in [load] and
 * reused by every [synthesize] call until [release].
 *
 * ## On the exact sherpa-onnx API surface used here
 * The class/field names below (`OfflineTtsSupertonicModelConfig.durationPredictor`
 * etc., `GenerationConfig.extra`, `generateWithConfigAndCallback`) are the ones
 * specified for this integration. This environment has no network access to
 * fetch or decompile the real `sherpa-onnx-1.13.5.aar`, so they could not be
 * independently verified against the actual artifact — once the real AAR is
 * added (see `app/libs/README.md`), a compile error here means only a field
 * name needs correcting against the AAR's real API, not a design change.
 */
@Singleton
class SupertonicTtsEngine @Inject constructor(
    private val provisioner: SupertonicAssetProvisioner,
) : NeuralTtsEngine {

    override val id = NeuralTtsEngines.SUPERTONIC
    override val label = "Supertonic 3 (locale)"

    // Bundled, not user-imported: nothing to ask for in Settings. See class doc.
    override val requiredAssets: Set<TtsAssetKind> = emptySet()

    /** Supertonic reports its own rate once loaded; this is only the pre-load fallback. */
    override val sampleRate: Int get() = tts?.let { sampleRateOf(it) } ?: DEFAULT_SAMPLE_RATE

    override val isLoaded: Boolean get() = tts != null

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var config: SupertonicConfig = SupertonicConfig.default(BuildConfig.DEBUG)
    @Volatile private var cancelRequested = false
    private val loadMutex = Mutex()

    /** Diagnostics-only: lets the "Voce JARVIS" debug panel A/B the three presets. */
    fun setQualityProfile(quality: SupertonicQuality) {
        config = config.copy(quality = quality)
    }

    fun currentProfile(): SupertonicQuality = config.quality

    /**
     * Ignores [model]/[voices]/[vocabulary]: this engine is self-contained (see
     * class doc). Extracts the bundle from `assets/` if needed, then builds the
     * native session off the calling (already-background) coroutine dispatcher.
     */
    override suspend fun load(model: File, voices: File?, vocabulary: File?): TtsLoadResult =
        withContext(Dispatchers.IO) {
            loadMutex.withLock {
                release()
                when (val bundle = provisioner.ensureExtracted()) {
                    SupertonicBundle.NotBundled ->
                        return@withLock TtsLoadResult.Failed("modello Supertonic non incluso in questa build")
                    is SupertonicBundle.Incomplete ->
                        return@withLock TtsLoadResult.Failed(
                            "modello Supertonic incompleto: mancano ${bundle.missing.size} file",
                        )
                    is SupertonicBundle.Failed ->
                        return@withLock TtsLoadResult.Failed("estrazione modello non riuscita: ${bundle.reason}")
                    is SupertonicBundle.Ready -> {
                        val dir = bundle.dir
                        try {
                            val supertonicConfig = OfflineTtsSupertonicModelConfig(
                                durationPredictor = File(dir, SupertonicAssetFiles.DURATION_PREDICTOR).absolutePath,
                                textEncoder = File(dir, SupertonicAssetFiles.TEXT_ENCODER).absolutePath,
                                vectorEstimator = File(dir, SupertonicAssetFiles.VECTOR_ESTIMATOR).absolutePath,
                                vocoder = File(dir, SupertonicAssetFiles.VOCODER).absolutePath,
                                ttsJson = File(dir, SupertonicAssetFiles.TTS_JSON).absolutePath,
                                unicodeIndexer = File(dir, SupertonicAssetFiles.UNICODE_INDEXER).absolutePath,
                                voiceStyle = File(dir, SupertonicAssetFiles.VOICE).absolutePath,
                            )
                            val modelConfig = OfflineTtsModelConfig(
                                supertonic = supertonicConfig,
                                numThreads = config.numThreads,
                                debug = config.debug,
                                provider = config.provider,
                            )
                            val offlineConfig = OfflineTtsConfig(model = modelConfig)
                            val created = OfflineTts(assetManager = null, config = offlineConfig)
                            tts = created

                            TtsLoadResult.Ok(
                                NeuralTtsInfo(
                                    engineId = id,
                                    voices = voices(),
                                    sampleRate = sampleRateOf(created),
                                    vocabularySource = SupertonicAssetFiles.TTS_JSON,
                                    detail = "lingua=${config.language} provider=${config.provider} " +
                                        "thread=${config.numThreads} passi=${config.numSteps} " +
                                        "(${config.quality.name.lowercase()})",
                                ),
                            )
                        } catch (e: Throwable) {
                            release()
                            Log.w(TAG, "supertonic_load_failed ${e.javaClass.simpleName}")
                            TtsLoadResult.Failed(e.javaClass.simpleName)
                        }
                    }
                }
            }
        }

    /**
     * A bundled voice.bin is one style, not a named list to choose from — the
     * single voice is exposed under a fixed, user-facing name so the existing
     * voice picker (built for Kokoro/Piper's real multi-voice packs) still has
     * something to show and select without special-casing a single-voice engine.
     */
    override fun voices(): List<String> = listOf(SINGLE_VOICE_NAME)

    override suspend fun peekVoices(model: File?, voices: File?, vocabulary: File?): List<String> =
        if (provisioner.isBundlePresent()) listOf(SINGLE_VOICE_NAME) else emptyList()

    override fun isReadyToLoad(): Boolean = isLoaded || provisioner.isBundlePresent()

    override suspend fun synthesize(text: String, voice: String, speed: Float): FloatArray? =
        withContext(Dispatchers.Default) {
            val engine = tts ?: return@withContext null
            if (text.isBlank()) return@withContext null
            cancelRequested = false

            val generationConfig = GenerationConfig(
                sid = 0,
                speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
                numSteps = config.numSteps,
                extra = mapOf("lang" to config.language),
            )

            // Segments arrive as they're generated; buffering them ourselves is
            // what lets cancelSynthesis() cut generation short mid-sentence
            // instead of only skipping sentences that have not started yet.
            val collected = ArrayList<FloatArray>()
            var totalSamples = 0
            try {
                val callback = { samples: FloatArray ->
                    synchronized(collected) {
                        collected += samples
                        totalSamples += samples.size
                    }
                    if (cancelRequested) 0 else 1
                }
                val audio: GeneratedAudio =
                    engine.generateWithConfigAndCallback(text, generationConfig, callback)
                // Prefer whatever the call itself returns (it may already be the
                // full concatenated buffer); fall back to what the callback
                // collected only if the return value came back empty, e.g. on a
                // build where the callback IS the only delivery mechanism.
                val fromReturn = audio.samples
                if (fromReturn.isNotEmpty()) fromReturn else flatten(collected, totalSamples)
            } catch (e: Throwable) {
                Log.w(TAG, "supertonic_synth_failed ${e.javaClass.simpleName}")
                null
            }
        }

    /**
     * Asks the native callback loop to stop returning further audio. Native
     * generation for the samples already in flight when this is set still
     * completes its current internal step — sherpa-onnx has no lower-level
     * abort — but no further chunk callback is honoured, which is the most a
     * callback-shaped API can offer short of killing the whole session.
     */
    override fun cancelSynthesis() {
        cancelRequested = true
    }

    override fun release() {
        runCatching { tts?.release() }
        tts = null
        cancelRequested = false
    }

    private fun flatten(parts: List<FloatArray>, total: Int): FloatArray {
        val out = FloatArray(total)
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    private fun sampleRateOf(engine: OfflineTts): Int =
        runCatching { engine.sampleRate() }.getOrDefault(DEFAULT_SAMPLE_RATE)

    private companion object {
        const val TAG = "JarvisSupertonic"
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val SINGLE_VOICE_NAME = "supertonic"
    }
}
