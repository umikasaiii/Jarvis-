package com.simone.jarvismobile.llm

import android.util.Log
import com.simone.jarvismobile.core.semantic.embedding.EmbeddingMath
import com.simone.jarvismobile.core.semantic.embedding.EmbeddingVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * § FASE 2A.11 §1, extended by PASSAGGIO 13 §D/§E/§K/§O — the real, on-device
 * EmbeddingGemma 300M engine: LiteRT (`org.tensorflow.lite.Interpreter` — the
 * base TFLite runtime, see `libs.versions.toml`'s own note on why
 * `litertlm-android` cannot be reused here) loads
 * `embeddinggemma-300M_seq256_mixed-precision.tflite` and runs one forward
 * pass per [embed] call — never [LlmEngine.generate]'s token-by-token decode
 * loop, which this model doesn't have at all.
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
 * pubblica). [validateTensorContract] checks the REAL loaded graph's tensor
 * count/rank/dtype against that expectation at load time and REJECTS the
 * load explicitly (never continues to inference) on any mismatch — the
 * first real verification is still a real device load, but a bad contract
 * now fails loudly via [lastLoadDetail] instead of crashing or silently
 * producing garbage embeddings.
 *
 * Also today gated closed regardless of tensor shape: [SemanticTokenizer]'s
 * production implementation is [NotReadyTokenizer] (PASSAGGIO 13 §D — no
 * real SentencePiece binding has been verified here), so [load] always fails
 * at the tokenizer step before ever reaching inference.
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
    private val tokenizer: SemanticTokenizer = NotReadyTokenizer()
    private val callMutex = Mutex()

    @Volatile private var justLoaded = false
    override var embeddingDimension: Int? = null
        private set

    override var modelSha256: String? = null
        private set

    override var tokenizerSha256: String? = null
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
                val tokenizerFile = File(tokenizerPath)
                if (!tokenizerFile.exists() || tokenizerFile.length() == 0L) {
                    _lastLoadDetail.value = "tokenizer_file_missing_or_empty"
                    _loadState.value = EmbeddingLoadState.ERROR
                    return@withContext false
                }

                val newInterpreter = Interpreter(modelFile, Interpreter.Options().apply { setNumThreads(4) })
                val contractIssue = validateTensorContract(newInterpreter)
                if (contractIssue != null) {
                    newInterpreter.close()
                    Log.w(TAG, "embedding_tensor_contract_rejected $contractIssue")
                    _lastLoadDetail.value = "tensor_contract_rejected:$contractIssue"
                    _loadState.value = EmbeddingLoadState.ERROR
                    return@withContext false
                }

                // § PASSAGGIO 13 §D — never a production tokenizer today, see
                // [NotReadyTokenizer]'s doc comment. Checked AFTER the tensor
                // contract so a bad graph is reported as such even before a
                // real tokenizer exists to reach inference with.
                if (!tokenizer.load(tokenizerPath)) {
                    newInterpreter.close()
                    _lastLoadDetail.value = "tokenizer_load_failed"
                    _loadState.value = EmbeddingLoadState.ERROR
                    return@withContext false
                }

                interpreter?.close()
                val outputShape = newInterpreter.getOutputTensor(0).shape()
                embeddingDimension = outputShape.lastOrNull()
                modelSha256 = sha256Of(modelFile)
                tokenizerSha256 = sha256Of(tokenizerFile)
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

    /**
     * § PASSAGGIO 13 §H — rejects a loaded graph whose real tensor shapes/
     * dtypes/rank don't match what this engine's [encode]/[pool] code
     * assumes, BEFORE any inference is ever attempted against it. Returns a
     * short machine-readable reason string, or `null` if the contract holds.
     */
    private fun validateTensorContract(interpreter: Interpreter): String? {
        val inputCount = interpreter.inputTensorCount
        if (inputCount < 2) return "input_count=$inputCount expected>=2"
        for (i in 0 until 2) {
            val t = interpreter.getInputTensor(i)
            val shape = t.shape()
            if (shape.size != 2) return "input[$i]_rank=${shape.size} expected=2"
            if (t.dataType() != DataType.INT32) return "input[$i]_dtype=${t.dataType()} expected=INT32"
        }
        val outputCount = interpreter.outputTensorCount
        if (outputCount < 1) return "output_count=$outputCount expected>=1"
        val out = interpreter.getOutputTensor(0)
        val outShape = out.shape()
        if (outShape.size != 2 && outShape.size != 3) return "output_rank=${outShape.size} expected=2or3"
        if (out.dataType() != DataType.FLOAT32) return "output_dtype=${out.dataType()} expected=FLOAT32"
        val hidden = outShape.lastOrNull() ?: return "output_shape_empty"
        if (hidden <= 0) return "output_hidden_dim=$hidden expected>0"
        return null
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun unload() {
        interpreter?.close()
        interpreter = null
        embeddingDimension = null
        modelSha256 = null
        tokenizerSha256 = null
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
                if (!isFiniteAndNonZero(pooled)) {
                    Log.w(TAG, "embed_rejected_invalid_output")
                    return@withContext null
                }
                val normalized = EmbeddingMath.l2Normalize(pooled)
                lastTiming = EmbeddingEngineTiming(
                    tokenizationMs = tokenMs,
                    embeddingMs = inferenceMs,
                    coldStartMs = if (coldStart) System.currentTimeMillis() - startedAt else null,
                )
                normalized
            } catch (e: Exception) {
                Log.w(TAG, "embed_failed ${e.javaClass.simpleName}")
                null
            }
        }
    }

    override var lastTiming: EmbeddingEngineTiming? = null
        private set

    /**
     * § PASSAGGIO 13 §H — a fail-closed guard rejecting any embedding that
     * is entirely zero (a degenerate/invalid model output — see
     * [EmbeddingMath.l2Normalize]'s own note that a zero vector can only
     * come from a genuinely broken output) or contains a `NaN`/`Infinity`
     * component (native inference corruption) — never silently poisons a
     * classification with an unusable vector.
     */
    private fun isFiniteAndNonZero(v: EmbeddingVector): Boolean {
        var sawNonZero = false
        for (x in v) {
            if (x.isNaN() || x.isInfinite()) return false
            if (x != 0f) sawNonZero = true
        }
        return sawNonZero
    }

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
