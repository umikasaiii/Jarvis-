package com.simone.jarvismobile.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.simone.jarvismobile.core.tts.ItalianG2P
import com.simone.jarvismobile.core.tts.PhonemeVocabulary
import com.simone.jarvismobile.core.tts.VoiceBank
import com.simone.jarvismobile.core.tts.VoiceBankReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kokoro v1.0 (INT8 or float ONNX) running under ONNX Runtime.
 *
 * The graph takes three inputs: the phoneme token ids, a 256-wide style vector
 * chosen by utterance length, and a speed scalar. Text never reaches it — the
 * Italian phonemiser in `:core` does that job, which is why the whole
 * pronunciation layer is unit-tested on the JVM instead of only on a phone.
 *
 * The session is created once and reused. Loading a ~90 MB quantised graph takes
 * seconds; doing it per sentence would make streaming pointless.
 */
@Singleton
class KokoroTtsEngine @Inject constructor() : NeuralTtsEngine {

    override val id = NeuralTtsEngines.KOKORO
    override val label = "Kokoro v1.0 (ONNX)"
    override val requiredAssets = setOf(TtsAssetKind.MODEL, TtsAssetKind.VOICES)
    override val optionalAssets = setOf(TtsAssetKind.VOCABULARY)

    override fun assetLabel(kind: TtsAssetKind): String = when (kind) {
        TtsAssetKind.MODEL -> "Modello (kokoro-v1.0.*.onnx)"
        TtsAssetKind.VOICES -> "Voci (voices-v1.0.bin)"
        TtsAssetKind.VOCABULARY -> "Vocabolario (tokens.txt o config.json)"
    }

    /** Kokoro always emits 24 kHz mono. */
    override val sampleRate = 24_000

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var bank: VoiceBank? = null
    private var vocab: PhonemeVocabulary = PhonemeVocabulary.BUILT_IN

    private var tokensInput = "input_ids"
    private var styleInput = "style"
    private var speedInput = "speed"

    override val isLoaded: Boolean get() = session != null

    override suspend fun load(model: File, voices: File?, vocabulary: File?): TtsLoadResult =
        withContext(Dispatchers.IO) {
            release()
            if (!model.isFile) return@withContext TtsLoadResult.Failed("modello non trovato")
            if (voices == null || !voices.isFile) {
                return@withContext TtsLoadResult.Failed("file voci mancante")
            }
            try {
                // An imported tokens.txt / config.json is authoritative; the
                // built-in table is only a fallback and says so in the UI.
                vocab = vocabulary
                    ?.takeIf { it.isFile }
                    ?.let { runCatching { PhonemeVocabulary.parse(it.readText()) }.getOrNull() }
                    ?: PhonemeVocabulary.BUILT_IN

                val loadedBank = voices.inputStream().use { VoiceBankReader.read(it) }
                if (loadedBank.isEmpty) {
                    return@withContext TtsLoadResult.Failed("nessuna voce nel file voci")
                }
                bank = loadedBank

                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(INFERENCE_THREADS)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val created = environment.createSession(model.absolutePath, options)
                env = environment
                session = created

                // Exports differ in what they call their inputs, so bind by
                // matching the real names and fall back to position.
                val names = created.inputNames.toList()
                tokensInput = names.firstOrNull { it.contains("input") || it.contains("token") }
                    ?: names.getOrElse(0) { "input_ids" }
                styleInput = names.firstOrNull { it.contains("style") || it.contains("ref") }
                    ?: names.getOrElse(1) { "style" }
                speedInput = names.firstOrNull { it.contains("speed") || it.contains("rate") }
                    ?: names.getOrElse(2) { "speed" }

                TtsLoadResult.Ok(
                    NeuralTtsInfo(
                        engineId = id,
                        voices = loadedBank.names,
                        sampleRate = sampleRate,
                        vocabularySource = vocab.source,
                        detail = "input=$tokensInput style=$styleInput speed=$speedInput " +
                            "voci=${loadedBank.names.size} simboli=${vocab.size}",
                    ),
                )
            } catch (e: Throwable) {
                release()
                Log.w(TAG, "kokoro_load_failed ${e.javaClass.simpleName}")
                TtsLoadResult.Failed(e.javaClass.simpleName)
            }
        }

    override fun voices(): List<String> = bank?.names.orEmpty()

    override suspend fun peekVoices(model: File?, voices: File?, vocabulary: File?): List<String> =
        withContext(Dispatchers.IO) {
            val file = voices?.takeIf { it.isFile } ?: return@withContext emptyList()
            runCatching { file.inputStream().use { VoiceBankReader.readNames(it) } }.getOrDefault(emptyList())
        }

    override suspend fun synthesize(text: String, voice: String, speed: Float): FloatArray? =
        withContext(Dispatchers.Default) {
            val ortSession = session ?: return@withContext null
            val environment = env ?: return@withContext null
            val styles = bank?.get(voice) ?: bank?.let { b -> b.names.firstOrNull()?.let(b::get) }
                ?: return@withContext null

            val phonemes = ItalianG2P.phonemize(text)
            if (phonemes.isBlank()) return@withContext null
            val body = vocab.encode(phonemes)
            if (body.isEmpty()) return@withContext null

            // The graph is padded with the 0 token at both ends, and the style
            // row is picked by the UNPADDED length — that is how the pack is
            // indexed, and an off-by-one here changes the prosody of every line.
            val trimmed = if (body.size > MAX_TOKENS) body.copyOf(MAX_TOKENS) else body
            val ids = LongArray(trimmed.size + 2)
            for (i in trimmed.indices) ids[i + 1] = trimmed[i].toLong()

            val style = styles.styleFor(trimmed.size)

            try {
                OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(ids),
                    longArrayOf(1, ids.size.toLong()),
                ).use { tokens ->
                    OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(style),
                        longArrayOf(1, style.size.toLong()),
                    ).use { styleTensor ->
                        OnnxTensor.createTensor(
                            environment,
                            FloatBuffer.wrap(floatArrayOf(speed)),
                            longArrayOf(1),
                        ).use { speedTensor ->
                            val inputs = mapOf(
                                tokensInput to tokens,
                                styleInput to styleTensor,
                                speedInput to speedTensor,
                            )
                            ortSession.run(inputs).use { result ->
                                flatten(result[0].value)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "kokoro_synth_failed ${e.javaClass.simpleName}")
                null
            }
        }

    /** The waveform comes back as float[], float[1][n] or float[1][1][n]. */
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
        // OrtEnvironment is process-wide and shared; closing it would break any
        // other session. Only the session and the voice pack are ours to free.
        env = null
        bank = null
    }

    private companion object {
        const val TAG = "JarvisKokoro"
        const val INFERENCE_THREADS = 2

        /**
         * Kokoro packs cover utterances up to ~510 tokens. Anything longer is a
         * caller bug — the pipeline splits replies into sentences — but a hard
         * cap is cheaper than a crash inside the graph.
         */
        const val MAX_TOKENS = 508
    }
}
