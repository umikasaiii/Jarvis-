package com.simone.jarvismobile.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.simone.jarvismobile.core.memory.Embedder
import com.simone.jarvismobile.core.memory.WordPieceTokenizer
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device sentence embedder over ONNX Runtime (already used by the neural
 * voices). It tokenises with the pure-Kotlin [WordPieceTokenizer], runs a
 * BERT-style model and mean-pools the token vectors into one L2-normalised
 * embedding. The model file and its `vocab.txt` are imported by the user, exactly
 * like the LLM and voice models — nothing is bundled, inference never touches the
 * network. If the graph's shapes don't match, [load] returns null and retrieval
 * stays lexical.
 */
class OnnxEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: WordPieceTokenizer,
) : Embedder {

    override var dimension: Int = 0
        private set

    private val inputNames: Set<String> = session.inputNames

    override fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        val seq = ids.size
        val shape = longArrayOf(1, seq.toLong())
        val idsLong = LongArray(seq) { ids[it].toLong() }
        val mask = LongArray(seq) { 1L }
        val types = LongArray(seq) { 0L }

        val open = ArrayList<OnnxTensor>(3)
        val inputs = LinkedHashMap<String, OnnxTensor>(3)
        fun bind(name: String, values: LongArray) {
            if (name in inputNames) {
                val tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)
                open += tensor
                inputs[name] = tensor
            }
        }
        bind("input_ids", idsLong)
        bind("attention_mask", mask)
        bind("token_type_ids", types)

        return try {
            session.run(inputs).use { result -> pool(result[0].value, mask) }
        } finally {
            open.forEach { it.close() }
        }
    }

    /** Mean-pool a `[1, seq, hidden]` output (masked), or take a `[1, hidden]` pooled output. */
    private fun pool(output: Any?, mask: LongArray): FloatArray {
        val batch = output as? Array<*> ?: return FloatArray(0)
        when (val first = batch.firstOrNull()) {
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                val tokens = first as Array<FloatArray>
                if (tokens.isEmpty()) return FloatArray(0)
                val hidden = tokens[0].size
                val sum = DoubleArray(hidden)
                var count = 0
                for (i in tokens.indices) {
                    if (i < mask.size && mask[i] == 0L) continue
                    count++
                    val row = tokens[i]
                    for (h in 0 until hidden) sum[h] += row[h]
                }
                val n = if (count > 0) count else 1
                return normalize(FloatArray(hidden) { (sum[it] / n).toFloat() })
            }
            is FloatArray -> return normalize(first.copyOf())
            else -> return FloatArray(0)
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0.0
        for (x in v) norm += x * x
        val d = sqrt(norm).toFloat()
        if (d > 0f) for (i in v.indices) v[i] /= d
        dimension = v.size
        return v
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** Loads the model + vocab, or returns null on any problem (caller falls back to lexical). */
        fun load(modelPath: String, vocabPath: String): OnnxEmbedder? = runCatching {
            val vocab = File(vocabPath).readLines()
                .mapIndexed { index, line -> line.trim() to index }
                .filter { it.first.isNotEmpty() }
                .toMap()
            if (vocab.isEmpty()) return null
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            val session = env.createSession(modelPath, options)
            OnnxEmbedder(env, session, WordPieceTokenizer(vocab))
        }.getOrNull()
    }
}
