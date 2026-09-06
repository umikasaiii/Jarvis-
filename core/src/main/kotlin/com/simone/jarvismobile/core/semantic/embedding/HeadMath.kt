package com.simone.jarvismobile.core.semantic.embedding

import kotlin.math.exp

/**
 * § FASE 2A.11 ADDENDUM §10 — pure Kotlin matrix inference for a trained
 * [ExportedHead]: a linear head is one matmul, a one-hidden-layer MLP is two
 * matmuls with a ReLU between. Deliberately plain `FloatArray`/
 * `List<List<Float>>` loops, no ND4J/TensorFlow-Lite/ONNX Runtime — a head
 * this small (a few thousand parameters at most) makes pulling in a second
 * ML runtime pure overhead; [EmbeddingGemmaEngine][com.simone.jarvismobile.llm.EmbeddingGemmaEngine]
 * (the actual TFLite `Interpreter`, in `app/`) remains the only place a
 * heavier runtime is justified — it runs the real 300M-parameter embedding
 * model, not this.
 */
object HeadMath {

    fun linearForward(weights: LinearWeights, x: FloatArray): FloatArray =
        FloatArray(weights.bias.size) { i ->
            val row = weights.weight[i]
            var sum = weights.bias[i]
            for (j in x.indices) sum += row[j] * x[j]
            sum
        }

    fun mlpForward(weights: MlpWeights, x: FloatArray): FloatArray {
        val hiddenSize = weights.b1.size
        val hidden = FloatArray(hiddenSize) { h ->
            var sum = weights.b1[h]
            for (f in x.indices) sum += x[f] * weights.w1[f][h]
            if (sum > 0f) sum else 0f // ReLU
        }
        val outputSize = weights.b2.size
        return FloatArray(outputSize) { o ->
            var sum = weights.b2[o]
            for (h in 0 until hiddenSize) sum += hidden[h] * weights.w2[h][o]
            sum
        }
    }

    /** Dispatches on [ExportedHead.architecture] — the one place this distinction is made. */
    fun forward(head: ExportedHead, x: FloatArray): FloatArray = when (head.architecture) {
        "mlp" -> mlpForward(requireNotNull(head.mlp) { "mlp architecture declared but mlp weights are null" }, x)
        else -> linearForward(requireNotNull(head.linear) { "linear architecture declared but linear weights are null" }, x)
    }

    fun softmax(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) return logits
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exps.sum().let { if (it > 0f) it else 1f }
        return FloatArray(logits.size) { exps[it] / sum }
    }

    fun sigmoid(z: Float): Float = (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
}
