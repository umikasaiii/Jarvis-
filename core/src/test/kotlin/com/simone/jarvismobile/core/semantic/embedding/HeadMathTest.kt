package com.simone.jarvismobile.core.semantic.embedding

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class HeadMathTest {

    private fun assertArrayCloseTo(expected: FloatArray, actual: FloatArray, tolerance: Float = 1e-4f) {
        assertTrue(expected.size == actual.size, "sizes differ: expected ${expected.size}, actual ${actual.size}")
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - actual[i]) < tolerance, "index $i: expected ${expected[i]} but was ${actual[i]}")
        }
    }

    @Test
    fun `linear forward is weight dot x plus bias, per output row`() {
        val weights = LinearWeights(weight = listOf(listOf(1f, 2f), listOf(3f, 4f)), bias = listOf(10f, 20f))
        val out = HeadMath.linearForward(weights, floatArrayOf(1f, 1f))
        // out0 = 1*1 + 2*1 + 10 = 13 ; out1 = 3*1 + 4*1 + 20 = 27
        assertArrayCloseTo(floatArrayOf(13f, 27f), out)
    }

    @Test
    fun `mlp forward applies relu between the two matmuls`() {
        val weights = MlpWeights(
            w1 = listOf(listOf(1f, 0f), listOf(0f, 1f)),
            b1 = listOf(0f, 0f),
            w2 = listOf(listOf(1f, 1f), listOf(1f, -1f)),
            b2 = listOf(0f, 0f),
        )
        val out = HeadMath.mlpForward(weights, floatArrayOf(2f, 3f))
        // hidden = relu([2,3]) = [2,3] (no negative to clip)
        // out0 = 2*1+3*1 = 5 ; out1 = 2*1+3*-1 = -1
        assertArrayCloseTo(floatArrayOf(5f, -1f), out)
    }

    @Test
    fun `mlp forward actually zeroes a negative pre-activation, not just passes it through`() {
        val weights = MlpWeights(
            w1 = listOf(listOf(-1f, 0f), listOf(0f, 1f)),
            b1 = listOf(0f, 0f),
            w2 = listOf(listOf(1f, 1f), listOf(1f, -1f)),
            b2 = listOf(0f, 0f),
        )
        val out = HeadMath.mlpForward(weights, floatArrayOf(2f, 3f))
        // hidden_pre = [-2, 3] -> relu -> [0, 3]
        // out0 = 0*1+3*1 = 3 ; out1 = 0*1+3*-1 = -3
        assertArrayCloseTo(floatArrayOf(3f, -3f), out)
    }

    @Test
    fun `forward dispatches on architecture field`() {
        val linearHead = ExportedHead(
            "linear", listOf("a", "b"),
            linear = LinearWeights(listOf(listOf(1f), listOf(0f)), listOf(0f, 0f)),
        )
        val out = HeadMath.forward(linearHead, floatArrayOf(5f))
        assertArrayCloseTo(floatArrayOf(5f, 0f), out)
    }

    @Test
    fun `softmax sums to one and preserves ranking order`() {
        val probs = HeadMath.softmax(floatArrayOf(3f, 1f, 2f))
        assertTrue(abs(probs.sum() - 1f) < 1e-4f)
        assertTrue(probs[0] > probs[2] && probs[2] > probs[1])
    }

    @Test
    fun `softmax on equal logits is uniform`() {
        val probs = HeadMath.softmax(floatArrayOf(1f, 1f, 1f))
        assertArrayCloseTo(floatArrayOf(1f / 3, 1f / 3, 1f / 3), probs, tolerance = 1e-3f)
    }

    @Test
    fun `sigmoid at zero is one half, saturates toward 0 and 1`() {
        assertTrue(abs(HeadMath.sigmoid(0f) - 0.5f) < 1e-4f)
        assertTrue(HeadMath.sigmoid(20f) > 0.999f)
        assertTrue(HeadMath.sigmoid(-20f) < 0.001f)
    }
}
