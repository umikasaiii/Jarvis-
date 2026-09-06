package com.simone.jarvismobile.core.semantic.embedding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CentroidClassifierTest {

    @Test
    fun `single-label classifier picks the nearest centroid`() {
        val classifier = SingleLabelCentroidClassifier(
            mapOf(
                "A" to listOf(floatArrayOf(1f, 0f)),
                "B" to listOf(floatArrayOf(0f, 1f)),
            ),
        )
        val result = classifier.classify(floatArrayOf(0.9f, 0.1f))
        assertEquals("A", result.best)
    }

    @Test
    fun `with only one label, margin equals bestScore - there is no runner-up to subtract`() {
        val classifier = SingleLabelCentroidClassifier(mapOf("A" to listOf(floatArrayOf(1f, 0f))))
        val result = classifier.classify(floatArrayOf(1f, 0f))
        assertEquals(null, result.secondBest)
        assertEquals(result.bestScore, result.margin)
    }

    @Test
    fun `margin is large when the query is unambiguously close to one label`() {
        val classifier = SingleLabelCentroidClassifier(
            mapOf(
                "A" to listOf(floatArrayOf(1f, 0f)),
                "B" to listOf(floatArrayOf(-1f, 0f)),
            ),
        )
        val result = classifier.classify(floatArrayOf(1f, 0f))
        assertTrue(result.margin > 1.5f) // A scores ~1, B scores ~-1
    }

    @Test
    fun `margin is small when two labels are almost equally close`() {
        val classifier = SingleLabelCentroidClassifier(
            mapOf(
                "A" to listOf(floatArrayOf(1f, 0.01f)),
                "B" to listOf(floatArrayOf(1f, -0.01f)),
            ),
        )
        val result = classifier.classify(floatArrayOf(1f, 0f))
        assertTrue(result.margin < 0.01f)
    }

    @Test
    fun `a label with multiple examples is a real centroid mean, not just the first example`() {
        val classifier = SingleLabelCentroidClassifier(
            mapOf("A" to listOf(floatArrayOf(2f, 0f), floatArrayOf(0f, 2f))),
        )
        // Centroid of (2,0) and (0,2), normalized to unit length, points along (1,1)/sqrt(2).
        val result = classifier.classify(floatArrayOf(1f, 1f))
        assertTrue(result.bestScore > 0.99f)
    }

    @Test
    fun `multi-label classifier scores every label independently`() {
        val classifier = MultiLabelCentroidClassifier(
            mapOf(
                "HEALTH" to listOf(floatArrayOf(1f, 0f, 0f)),
                "AGENDA" to listOf(floatArrayOf(0f, 1f, 0f)),
                "WEATHER" to listOf(floatArrayOf(0f, 0f, 1f)),
            ),
        )
        // A query aligned with both HEALTH and AGENDA, orthogonal to WEATHER.
        val scores = classifier.scoreAll(floatArrayOf(1f, 1f, 0f))
        assertTrue(scores.getValue("HEALTH") > 0.5f)
        assertTrue(scores.getValue("AGENDA") > 0.5f)
        assertTrue(scores.getValue("WEATHER") < 0.1f)
    }

    @Test
    fun `an empty label map classifies to no result, never a crash`() {
        val classifier = SingleLabelCentroidClassifier<String>(emptyMap())
        val result = classifier.classify(floatArrayOf(1f, 0f))
        assertEquals(null, result.best)
        assertEquals(0f, result.bestScore)
    }
}
