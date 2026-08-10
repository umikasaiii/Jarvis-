package com.simone.jarvismobile.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticRetrievalTest {

    // --- cosine -------------------------------------------------------------

    @Test fun identicalVectorsAreFullySimilar() {
        val v = floatArrayOf(0.2f, 0.5f, 0.1f)
        assertTrue(VectorMath.cosine(v, v) > 0.999f)
    }

    @Test fun orthogonalVectorsAreUnrelated() {
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
    }

    @Test fun oppositeVectorsAreNegative() {
        assertTrue(VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)) < -0.999f)
    }

    @Test fun mismatchedOrEmptyIsZeroNotCrash() {
        assertEquals(0f, VectorMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f)))
        assertEquals(0f, VectorMath.cosine(floatArrayOf(), floatArrayOf()))
    }

    // --- hybrid fusion ------------------------------------------------------

    @Test fun paraphraseWinsOnSemanticEvenWithNoLexicalOverlap() {
        // "espresso" note has zero lexical score for the query "bere", but a high
        // semantic score — it should still come first.
        val ranked = HybridRanker.fuse(
            listOf(
                HybridCandidate("espresso", lexicalScore = 0.0, semanticScore = 0.9),
                HybridCandidate("altro", lexicalScore = 0.0, semanticScore = 0.1),
            ),
        )
        assertEquals("espresso", ranked.first().ref)
    }

    @Test fun lexicalBreaksTiesWhenSemanticIsEqual() {
        val ranked = HybridRanker.fuse(
            listOf(
                HybridCandidate("a", lexicalScore = 10.0, semanticScore = 0.5),
                HybridCandidate("b", lexicalScore = 1.0, semanticScore = 0.5),
            ),
        )
        assertEquals("a", ranked.first().ref)
    }

    @Test fun bothSignalsCombine() {
        // High lexical + high semantic beats high-only-one.
        val ranked = HybridRanker.fuse(
            listOf(
                HybridCandidate("both", lexicalScore = 9.0, semanticScore = 0.8),
                HybridCandidate("lexOnly", lexicalScore = 10.0, semanticScore = -1.0),
                HybridCandidate("semOnly", lexicalScore = 0.0, semanticScore = 0.85),
            ),
        )
        assertEquals("both", ranked.first().ref)
    }

    @Test fun respectsLimitAndEmpty() {
        assertTrue(HybridRanker.fuse(emptyList()).isEmpty())
        val many = (1..10).map { HybridCandidate("r$it", it.toDouble(), 0.5) }
        assertEquals(3, HybridRanker.fuse(many, limit = 3).size)
    }
}
