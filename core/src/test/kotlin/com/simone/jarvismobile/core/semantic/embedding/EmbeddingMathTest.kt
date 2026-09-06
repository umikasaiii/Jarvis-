package com.simone.jarvismobile.core.semantic.embedding

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingMathTest {

    @Test
    fun `identical vectors have cosine similarity 1`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertTrue(abs(EmbeddingMath.cosineSimilarity(v, v) - 1f) < 1e-5f)
    }

    @Test
    fun `orthogonal vectors have cosine similarity 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, EmbeddingMath.cosineSimilarity(a, b))
    }

    @Test
    fun `opposite vectors have cosine similarity -1`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertTrue(abs(EmbeddingMath.cosineSimilarity(a, b) - -1f) < 1e-5f)
    }

    @Test
    fun `a zero vector never divides by zero - similarity is 0, not NaN`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val v = floatArrayOf(1f, 2f, 3f)
        val sim = EmbeddingMath.cosineSimilarity(zero, v)
        assertEquals(0f, sim)
    }

    @Test
    fun `l2Normalize produces a unit vector`() {
        val v = floatArrayOf(3f, 4f)
        val normalized = EmbeddingMath.l2Normalize(v)
        val norm = kotlin.math.sqrt(normalized[0] * normalized[0] + normalized[1] * normalized[1])
        assertTrue(abs(norm - 1f) < 1e-5f)
    }

    @Test
    fun `l2Normalize of a zero vector returns it unchanged, never NaN`() {
        val zero = floatArrayOf(0f, 0f)
        assertEquals(listOf(0f, 0f), EmbeddingMath.l2Normalize(zero).toList())
    }

    @Test
    fun `mean of two vectors is their midpoint`() {
        val a = floatArrayOf(0f, 0f)
        val b = floatArrayOf(2f, 4f)
        assertEquals(listOf(1f, 2f), EmbeddingMath.mean(listOf(a, b)).toList())
    }

    @Test
    fun `dot product requires matching dimensions`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertTrue(runCatching { EmbeddingMath.dot(a, b) }.isFailure)
    }
}
