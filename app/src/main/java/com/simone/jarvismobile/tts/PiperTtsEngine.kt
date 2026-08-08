package com.simone.jarvismobile.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.simone.jarvismobile.core.tts.ItalianG2P
import com.simone.jarvismobile.core.tts.PiperConfigReader
import com.simone.jarvismobile.core.tts.PiperVoiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Piper (VITS) running under ONNX Runtime.
 *
 * A Piper voice is two files — `it_IT-paola-medium.onnx` and its
 * `it_IT-paola-medium.onnx.json` — and the JSON is what makes this engine more
 * dependable than the Kokoro one: it carries the model's own `phoneme_id_map`,
 * its sample rate and its inference scales, so the token ids are right by
 * construction rather than by reconstruction. There is no voice pack; Kokoro's
 * `voices-v1.0.bin` has no meaning here and is never asked for.
 *
 * The graph takes phoneme ids, their length, and a scales triple
 * (noise, length, noise_w). Speed is length: dividing length_scale by the
 * requested rate is how Piper is made to talk faster, since the model has no
 * speed input of its own.
 *
 * Everything runs on the device; nothing on this path opens a socket.
 */
@Singleton
class PiperTtsEngine @Inject constructor() : NeuralTtsEngine {

    override val id = NeuralTtsEngines.PIPER
    override val label = "Piper (ONNX)"

    // No VOICES slot: a Piper voice is one model plus one config, and offering
    // the Kokoro pack here would only invite a confusing failure.
    override val requiredAssets = setOf(TtsAssetKind.MODEL, TtsAssetKind.VOCABULARY)

    override fun assetLabel(kind: TtsAssetKind): String = when (kind) {
        TtsAssetKind.MODEL -> "Modello (es. it_IT-paola-medium.onnx)"
        TtsAssetKind.VOCABULARY -> "Configurazione (es. it_IT-paola-medium.onnx.json)"
        TtsAssetKind.VOICES -> TtsAssetKind.VOICES.label
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var config: PiperVoiceConfig? = null
    private var speakerInput: String? = null

    override val sampleRate: Int get() = config?.sampleRate ?: DEFAULT_SAMPLE_RATE

    override val isLoaded: Boolean get() = session != null && config != null

    override suspend fun load(model: File, voices: File?, vocabulary: File?): TtsLoadResult =
        withContext(Dispatchers.IO) {
            release()
            if (!model.isFile) return@withContext TtsLoadResult.Failed("modello non trovato")

            // The config normally sits next to the model as "<model>.json", so
            // look there before insisting the user import it separately.
            val configFile = vocabulary?.takeIf { it.isFile }
                ?: File(model.absolutePath + ".json").takeIf { it.isFile }
                ?: return@withContext TtsLoadResult.Failed("config .onnx.json mancante")

            val parsed = runCatching { PiperConfigReader.parse(configFile.readText()) }.getOrNull()
                ?: return@withContext TtsLoadResult.Failed("config .onnx.json non valido")

            try {
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(INFERENCE_THREADS)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val created = environment.createSession(model.absolutePath, options)
                env = environment
                session = created
                config = parsed

                val names = created.inputNames.toList()
                // Only multi-speaker exports have a speaker id input; binding one
                // that is not there fails the whole run.
                speakerInput = names.firstOrNull { it == "sid" || it.contains("speaker") }
                    ?.takeIf { parsed.isMultiSpeaker }

                TtsLoadResult.Ok(
                    NeuralTtsInfo(
                        engineId = id,
                        voices = parsed.speakers,
                        sampleRate = parsed.sampleRate,
                        vocabularySource = configFile.name,
                        detail = "lingua=${parsed.language.ifBlank { "?" }} " +
                            "${parsed.sampleRate} Hz simboli=${parsed.phonemeIds.size} " +
                            "voci=${parsed.speakers.size} input=${names.joinToString(",")}",
                    ),
                )
            } catch (e: Throwable) {
                release()
                Log.w(TAG, "piper_load_failed ${e.javaClass.simpleName}")
                TtsLoadResult.Failed(e.javaClass.simpleName)
            }
        }

    override fun voices(): List<String> = config?.speakers.orEmpty()

    override suspend fun peekVoices(model: File?, voices: File?, vocabulary: File?): List<String> =
        withContext(Dispatchers.IO) {
            val file = vocabulary?.takeIf { it.isFile }
                ?: model?.let { File(it.absolutePath + ".json") }?.takeIf { it.isFile }
                ?: return@withContext emptyList()
            runCatching { PiperConfigReader.parse(file.readText())?.speakers }.getOrNull().orEmpty()
        }

    override suspend fun synthesize(text: String, voice: String, speed: Float): FloatArray? =
        withContext(Dispatchers.Default) {
            val ortSession = session ?: return@withContext null
            val environment = env ?: return@withContext null
            val cfg = config ?: return@withContext null

            val phonemes = ItalianG2P.phonemize(text)
            if (phonemes.isBlank()) return@withContext null
            val ids = cfg.encode(phonemes)
            // A sequence of nothing but the begin/end markers means every symbol
            // was dropped: the voice's table does not cover what we produced.
            if (ids.size <= MIN_USEFUL_IDS) {
                Log.w(TAG, "piper_no_known_phonemes len=${ids.size}")
                return@withContext null
            }

            val tokens = LongArray(ids.size) { ids[it].toLong() }
            // Piper slows down by stretching, so a faster rate is a shorter scale.
            val lengthScale = cfg.lengthScale / speed.coerceIn(MIN_SPEED, MAX_SPEED)
            val scales = floatArrayOf(cfg.noiseScale, lengthScale, cfg.noiseW)

            val closeables = ArrayList<OnnxTensor>(4)
            try {
                val inputTensor = OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(tokens),
                    longArrayOf(1, tokens.size.toLong()),
                ).also(closeables::add)
                val lengthTensor = OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(longArrayOf(tokens.size.toLong())),
                    longArrayOf(1),
                ).also(closeables::add)
                val scalesTensor = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(scales),
                    longArrayOf(3),
                ).also(closeables::add)

                val inputs = LinkedHashMap<String, OnnxTensor>(4)
                inputs["input"] = inputTensor
                inputs["input_lengths"] = lengthTensor
                inputs["scales"] = scalesTensor
                speakerInput?.let { name ->
                    inputs[name] = OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(longArrayOf(cfg.speakerId(voice).toLong())),
                        longArrayOf(1),
                    ).also(closeables::add)
                }

                ortSession.run(inputs).use { result -> flatten(result[0].value) }
            } catch (e: Throwable) {
                Log.w(TAG, "piper_synth_failed ${e.javaClass.simpleName}")
                null
            } finally {
                closeables.forEach { runCatching { it.close() } }
            }
        }

    /** Piper returns float[1][1][n]. */
    private fun flatten(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val parts = value.mapNotNull { flatten(it) }
            when {
                parts.isEmpty() -> null
                parts.size == 1 -> parts[0]
                else -> {
                    val out = FloatArray(parts.sumOf { it.size })
                    var at = 0
                    parts.forEach { it.copyInto(out, at); at += it.size }
                    out
                }
            }
        }
        else -> null
    }

    override fun release() {
        runCatching { session?.close() }
        session = null
        // The environment is process-wide and shared with the other engine;
        // closing it here would break that one too.
        env = null
        config = null
        speakerInput = null
    }

    private companion object {
        const val TAG = "JarvisPiper"
        const val INFERENCE_THREADS = 2
        const val DEFAULT_SAMPLE_RATE = 22_050
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /** Begin + end marker only: nothing of the sentence survived. */
        const val MIN_USEFUL_IDS = 4
    }
}
