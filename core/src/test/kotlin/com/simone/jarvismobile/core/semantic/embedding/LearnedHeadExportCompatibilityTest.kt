package com.simone.jarvismobile.core.semantic.embedding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §K/§N/§Y (test items
 * 6-9, 32, 33-35 as applicable to a static export). A trained head produced
 * under encoder contract X must never load against a runtime using
 * contract Y — and a non-[ArtifactQualification.PRODUCTION_ELIGIBLE] head
 * must never be accepted regardless of contract match.
 */
class LearnedHeadExportCompatibilityTest {

    private val head = ExportedHead(
        architecture = "linear",
        labels = listOf("A", "B"),
        linear = LinearWeights(weight = listOf(listOf(0f, 1f), listOf(0f, -1f)), bias = listOf(0f, 0f)),
    )

    private fun export(
        contract: SemanticEncoderContract? = SemanticEncoderContract.CURRENT,
        qualification: ArtifactQualification = ArtifactQualification.PRODUCTION_ELIGIBLE,
        embeddingDim: Int = 768,
    ) = LearnedHeadExport(
        schemaVersion = 1,
        embeddingDim = embeddingDim,
        intent = head,
        domain = head,
        encoderContract = contract,
        artifactQualification = qualification,
    )

    @Test
    fun `a production-eligible head with a matching contract is accepted`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        assertTrue(export().isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `a contract version mismatch rejects the head`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(
            contractVersion = SemanticEncoderContract.CURRENT.contractVersion + 1,
            embeddingDimension = 768,
        )
        assertFalse(export().isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `a tokenizer format mismatch rejects the head`() {
        val runtimeContract = SemanticEncoderContract.UNVERIFIED.copy(embeddingDimension = 768)
        assertFalse(export().isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `an embedding-dimension mismatch rejects the head`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 512)
        assertFalse(export(embeddingDim = 768).isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `a SYNTHETIC_SELFTEST head is never accepted, even with a perfectly matching contract`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        val synthetic = export(qualification = ArtifactQualification.SYNTHETIC_SELFTEST)
        assertFalse(synthetic.isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `a TRAINING_PENDING head is never accepted`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        val pending = export(qualification = ArtifactQualification.TRAINING_PENDING)
        assertFalse(pending.isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `an INCOMPATIBLE-qualified head is never accepted`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        val incompatible = export(qualification = ArtifactQualification.INCOMPATIBLE)
        assertFalse(incompatible.isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `a head with no encoder contract at all — pre-PASSAGGIO-13 export — is never accepted`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        val legacy = export(contract = null)
        assertFalse(legacy.isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `default artifactQualification for a bare export is TRAINING_PENDING, never accidentally eligible`() {
        val bareExport = LearnedHeadExport(schemaVersion = 1, embeddingDim = 4, intent = head, domain = head)
        assertFalse(ArtifactQualification.PRODUCTION_ELIGIBLE == bareExport.artifactQualification)
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 4)
        assertFalse(bareExport.isCompatibleWithRuntime(runtimeContract))
    }
}
