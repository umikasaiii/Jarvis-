package com.simone.jarvismobile.core.semantic.embedding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §Y (test items 5-9).
 */
class SemanticEncoderContractTest {

    @Test
    fun `contract round-trips through JSON without loss`() {
        val original = SemanticEncoderContract.CURRENT.copy(
            tokenizerSha256 = "abc123",
            modelSha256 = "def456",
            embeddingDimension = 768,
        )
        val json = SemanticEncoderContract.toJson(original)
        val decoded = SemanticEncoderContract.parseOrNull(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `malformed contract JSON parses to null, never throws`() {
        assertEquals(null, SemanticEncoderContract.parseOrNull("not json at all"))
        assertEquals(null, SemanticEncoderContract.parseOrNull("{}"))
    }

    @Test
    fun `identical contracts are compatible`() {
        assertTrue(SemanticEncoderContract.CURRENT.isCompatibleWith(SemanticEncoderContract.CURRENT))
    }

    @Test
    fun `a contract version mismatch rejects compatibility`() {
        val newer = SemanticEncoderContract.CURRENT.copy(contractVersion = SemanticEncoderContract.CURRENT.contractVersion + 1)
        assertFalse(SemanticEncoderContract.CURRENT.isCompatibleWith(newer))
    }

    @Test
    fun `an UNVERIFIED tokenizer format is never compatible with the CURRENT declared format`() {
        assertFalse(SemanticEncoderContract.UNVERIFIED.isCompatibleWith(SemanticEncoderContract.CURRENT))
        assertFalse(SemanticEncoderContract.CURRENT.isCompatibleWith(SemanticEncoderContract.UNVERIFIED))
    }

    @Test
    fun `a task prefix mismatch rejects compatibility`() {
        val withPrefix = SemanticEncoderContract.CURRENT.copy(taskPrefix = "query: ")
        assertFalse(SemanticEncoderContract.CURRENT.isCompatibleWith(withPrefix))
    }

    @Test
    fun `a pooling mode mismatch rejects compatibility`() {
        val nativePooling = SemanticEncoderContract.CURRENT.copy(poolingMode = PoolingMode.MODEL_NATIVE)
        assertFalse(SemanticEncoderContract.CURRENT.isCompatibleWith(nativePooling))
    }

    @Test
    fun `a normalization mode mismatch rejects compatibility`() {
        val unnormalized = SemanticEncoderContract.CURRENT.copy(embeddingNormalization = EmbeddingNormalization.NONE)
        assertFalse(SemanticEncoderContract.CURRENT.isCompatibleWith(unnormalized))
    }

    @Test
    fun `a max sequence length mismatch rejects compatibility`() {
        val shorter = SemanticEncoderContract.CURRENT.copy(maxSequenceLength = 128)
        assertFalse(SemanticEncoderContract.CURRENT.isCompatibleWith(shorter))
    }

    @Test
    fun `an embedding dimension mismatch rejects compatibility only when both sides know it`() {
        val a = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        val b = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 512)
        assertFalse(a.isCompatibleWith(b))
        // Neither side knowing the dimension yet must not be treated as a mismatch.
        assertTrue(SemanticEncoderContract.CURRENT.isCompatibleWith(SemanticEncoderContract.CURRENT))
    }

    @Test
    fun `a tokenizer hash mismatch rejects compatibility only when both sides have computed one`() {
        val withHashA = SemanticEncoderContract.CURRENT.copy(tokenizerSha256 = "aaa")
        val withHashB = SemanticEncoderContract.CURRENT.copy(tokenizerSha256 = "bbb")
        assertFalse(withHashA.isCompatibleWith(withHashB))
        // One side never having hashed a real file yet is not itself a mismatch.
        assertTrue(withHashA.isCompatibleWith(SemanticEncoderContract.CURRENT))
    }

    @Test
    fun `a model hash mismatch rejects compatibility only when both sides have computed one`() {
        val withHashA = SemanticEncoderContract.CURRENT.copy(modelSha256 = "aaa")
        val withHashB = SemanticEncoderContract.CURRENT.copy(modelSha256 = "bbb")
        assertFalse(withHashA.isCompatibleWith(withHashB))
    }

    @Test
    fun `special tokens and task prefix are honestly unverified in the CURRENT contract`() {
        assertFalse(SemanticEncoderContract.CURRENT.specialTokensVerified)
        assertFalse(SemanticEncoderContract.CURRENT.taskPrefixVerified)
        assertEquals(null, SemanticEncoderContract.CURRENT.bosTokenId)
        assertEquals(null, SemanticEncoderContract.CURRENT.eosTokenId)
        assertEquals(null, SemanticEncoderContract.CURRENT.padTokenId)
    }

    @Test
    fun `no artifact hash is ever invented — CURRENT starts with both hashes null`() {
        assertEquals(null, SemanticEncoderContract.CURRENT.tokenizerSha256)
        assertEquals(null, SemanticEncoderContract.CURRENT.modelSha256)
    }
}
