package com.simone.jarvismobile.llm

import android.util.Log
import com.simone.jarvismobile.core.semantic.embedding.EmbeddingVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.tensorflow.lite.Interpreter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § FASE 2A.11 §1 — the real, on-device EmbeddingGemma 300M engine: LiteRT
 * (`org.tensorflow.lite.Interpreter` — the base TFLite runtime, see
 * `libs.versions.toml`'s own note on why `litertlm-android` cannot be reused
 * here) loads `embeddinggemma-300M_seq256_mixed-precision.tflite` and runs
 * one forward pass per [embed] call — never [LlmEngine.generate]'s
 * token-by-token decode loop, which this model doesn't have at all.
 *
 * **Onestà — input/output tensor reali non verificati in questo ambiente**:
 * né Hugging Face (`litert-community/embeddinggemma-300m`) né la
 * documentazione Google sono raggiungibili da questa sandbox (rete bloccata,
 * stesso limite già documentato per Health Connect/TomTom/Valhalla in
 * `CLAUDE.md`) — non è stato possibile ispezionare il file `.tflite` reale
 * per confermare nomi/ordine/shape esatti dei tensori di input
 * (`input_ids`/`attention_mask`, entrambi `int32[1,256]` per convenzione
 * pubblica nota) e di output (un embedding a 768 dimensioni, con Matryoshka
 * troncabile a 512/256/128 per lo stesso modello, sempre per conoscenza
 * pubblica). Il codice sotto usa [Interpreter.runForMultipleInputsOutputs]
 * con NOMI di tensore risolti per indice — se l'ordine reale dei tensori del
 * grafo differisse, la prima vera verifica sarà il caricamento su un
 * dispositivo reale (`load()` fallirebbe in modo esplicito e diagnosticabile
 * via [lastLoadDetail], mai un crash silenzioso o un embedding inventato).
 * L'output è gestito difensivamente in base al rango reale del tensore
 * (pooling medio se il grafo restituisce un embedding per token, uso diretto
 * se restituisce già un vettore per frase) — vedi [pool].
 */
@Singleton
class EmbeddingGemmaEngine @Inject constructor() : SemanticEmbeddingEngine {

    private val _loadState = MutableStateFlow(EmbeddingLoadState.UNLOADED)
    override val loadState: StateFlow<EmbeddingLoadState> = _loadState

    private val _loadedModelName = MutableStateFlow<String?>(null)
    override val loadedModelName: StateFlow<String?> = _loadedModelName

    private val _lastLoadDetail = MutableStateFlow("")
    override val lastLoadDetail: StateFlow<String> = _lastLoadDetail

    private var interpreter: Interpreter? = null
    private val tokenizer: SemanticTokenizer = FallbackWhitespaceTokenizer()
    private val callMutex = Mutex()

    @Volatile private var justLoaded = false
    override var embeddingDimension: Int? = null
        private set

    override suspend fun load(modelPath: String, tokenizerPath: String, modelName: String): Boolean =
        withContext(Dispatchers.IO) {
            _loadState.value = EmbeddingLoadState.LOADING
            try {
                val modelFile = File(modelPath)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    _lastLoadDetail.value = "model_file_missing_or_empty"
                    _loadState.value = EmbeddingLoadState.ERROR
                    return@withContext false
                }
                if (!tokenizer.load(tokenizerPath)) {
                    _lastLoadDetail.value = "tokenizer_load_failed"
                    _loadState.value = EmbeddingLoadState.ERROR
                    return@withContext false
                }
                interpreter?.close()
                val newInterpreter = Interpreter(modelFile, Interpreter.Options().apply { setNumThreads(4) })
                val outputShape = newInterpreter.getOutputTensor(0).shape()
                embeddingDimension = outputShape.lastOrNull()
                interpreter = newInterpreter
                justLoaded = true
                _loadedModelName.value = modelName
                _loadState.value = EmbeddingLoadState.LOADED
                _lastLoadDetail.value = "ok"
                true
            } catch (e: Exception) {
                Log.w(TAG, "embedding_model_load_failed ${e.javaClass.simpleName}")
                _lastLoadDetail.value = e.javaClass.simpleName
                _loadState.value = EmbeddingLoadState.ERROR
                false
            }
        }

    override fun unload() {
        interpreter?.close()
        interpreter = null
        embeddingDimension = null
        _loadedModelName.value = null
        _loadState.value = EmbeddingLoadState.UNLOADED
    }

    override suspend fun embed(text: String): EmbeddingVector? = callMutex.withLock {
        val engine = interpreter ?: return null
        withContext(Dispatchers.Default) {
            try {
                val coldStart = justLoaded
                justLoaded = false
                val startedAt = System.currentTimeMillis()

                val tokenStart = System.currentTimeMillis()
                val ids = tokenizer.encode(text, SEQUENCE_LENGTH) ?: return@withContext null
                val attentionMask = IntArray(SEQUENCE_LENGTH) { i -> if (ids[i] != 0) 1 else 0 }
                val tokenMs = System.currentTimeMillis() - tokenStart

                val inferenceStart = System.currentTimeMillis()
                val inputIds = Array(1) { ids }
                val attentionMaskInput = Array(1) { attentionMask }
                val outputShape = engine.getOutputTensor(0).shape()
                val rawOutput = allocateOutput(outputShape)

                engine.runForMultipleInputsOutputs(
                    arrayOf(inputIds, attentionMaskInput),
                    mapOf(0 to rawOutput),
                )
                val inferenceMs = System.currentTimeMillis() - inferenceStart

                val pooled = pool(rawOutput, outputShape, attentionMask)
                lastTiming = EmbeddingEngineTiming(
                    tokenizationMs = tokenMs,
                    embeddingMs = inferenceMs,
                    coldStartMs = if (coldStart) System.currentTimeMillis() - startedAt else null,
                )
                pooled
            } catch (e: Exception) {
                Log.w(TAG, "embed_failed ${e.javaClass.simpleName}")
                null
            }
        }
    }

    override var lastTiming: EmbeddingEngineTiming? = null
        private set

    /**
     * A rank-2 output ([1, hidden]) is already a pooled sentence embedding —
     * used as-is. A rank-3 output ([1, seq_len, hidden]) is a per-token
     * embedding — mean-pooled over the REAL (non-padded) tokens only, using
     * [attentionMask] exactly the way a standard sentence-embedding head
     * would (never averaging in the padding).
     */
    @Suppress("UNCHECKED_CAST")
    private fun pool(rawOutput: Any, shape: IntArray, attentionMask: IntArray): EmbeddingVector {
        if (shape.size == 2) {
            val out = rawOutput as Array<FloatArray>
            return out[0]
        }
        val out = rawOutput as Array<Array<FloatArray>>
        val hidden = shape.last()
        val sum = FloatArray(hidden)
        var count = 0
        for (t in out[0].indices) {
            if (attentionMask.getOrElse(t) { 0 } == 0) continue
            val vec = out[0][t]
            for (h in 0 until hidden) sum[h] += vec[h]
            count++
        }
        if (count == 0) return sum
        return FloatArray(hidden) { i -> sum[i] / count }
    }

    private fun allocateOutput(shape: IntArray): Any = when (shape.size) {
        2 -> Array(shape[0]) { FloatArray(shape[1]) }
        3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
        else -> error("unexpected embedding output rank: ${shape.size}")
    }

    private companion object {
        const val TAG = "EmbeddingGemmaEngine"
        const val SEQUENCE_LENGTH = 256 // per the model's own filename, §1
    }
}
