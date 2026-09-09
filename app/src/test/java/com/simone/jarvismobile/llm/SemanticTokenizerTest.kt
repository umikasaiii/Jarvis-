package com.simone.jarvismobile.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §D. Pins the
 * production-tokenizer-disabled contract: [NotReadyTokenizer] (the real
 * [SemanticTokenizer] wired into [EmbeddingGemmaEngine]) must NEVER report
 * ready and NEVER encode, regardless of input — this is what makes the
 * whole semantic embedding classifier fail closed (`SEMANTIC_MODEL_UNAVAILABLE`)
 * rather than silently running inference against approximate/hashed token
 * ids. [FallbackWhitespaceTokenizer] (moved to this test-fixture package in
 * this same passage) is pinned separately as test-fixture-only — it must
 * still work for tests that need SOME real [SemanticTokenizer] to exercise
 * plumbing, but is never reachable from production code any more (verified
 * by [EmbeddingGemmaEngine]'s own source using [NotReadyTokenizer]).
 */
class SemanticTokenizerTest {

    // --- NotReadyTokenizer: production, always fail-closed ---

    @Test
    fun `NotReadyTokenizer never reports ready`() {
        val tokenizer = NotReadyTokenizer()
        assertFalse(tokenizer.isReady)
    }

    @Test
    fun `NotReadyTokenizer load always returns false, even against a real file`() {
        val realFile = File.createTempFile("tokenizer", ".model")
        realFile.writeText("not-empty")
        try {
            val tokenizer = NotReadyTokenizer()
            assertFalse(tokenizer.load(realFile.absolutePath))
            assertFalse(tokenizer.isReady)
        } finally {
            realFile.delete()
        }
    }

    @Test
    fun `NotReadyTokenizer encode always returns null regardless of prior load attempts`() {
        val tokenizer = NotReadyTokenizer()
        val realFile = File.createTempFile("tokenizer", ".model")
        realFile.writeText("not-empty")
        try {
            tokenizer.load(realFile.absolutePath)
            assertNull(tokenizer.encode("qualsiasi testo", 256))
            assertNull(tokenizer.encode("", 256))
        } finally {
            realFile.delete()
        }
    }

    // --- FallbackWhitespaceTokenizer: test-fixture-only, still functional ---

    @Test
    fun `FallbackWhitespaceTokenizer becomes ready only after a real non-empty file loads`() {
        val tokenizer = FallbackWhitespaceTokenizer()
        assertFalse(tokenizer.isReady)

        val missing = File.createTempFile("tokenizer", ".model").also { it.delete() }
        assertFalse(tokenizer.load(missing.absolutePath))
        assertFalse(tokenizer.isReady)

        val real = File.createTempFile("tokenizer", ".model")
        real.writeText("vocab")
        try {
            assertTrue(tokenizer.load(real.absolutePath))
            assertTrue(tokenizer.isReady)
        } finally {
            real.delete()
        }
    }

    @Test
    fun `FallbackWhitespaceTokenizer encode is deterministic and never null once ready`() {
        val real = File.createTempFile("tokenizer", ".model")
        real.writeText("vocab")
        try {
            val tokenizer = FallbackWhitespaceTokenizer()
            tokenizer.load(real.absolutePath)
            val first = tokenizer.encode("ciao mondo", 8)
            val second = tokenizer.encode("ciao mondo", 8)
            assertEquals(first!!.toList(), second!!.toList())
            assertEquals(8, first.size)
        } finally {
            real.delete()
        }
    }

    @Test
    fun `FallbackWhitespaceTokenizer encode returns null before any successful load`() {
        val tokenizer = FallbackWhitespaceTokenizer()
        assertNull(tokenizer.encode("testo", 8))
    }
}
