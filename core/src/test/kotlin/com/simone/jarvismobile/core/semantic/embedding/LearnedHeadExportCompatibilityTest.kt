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

    // --- PASSAGGIO 14 §S/§Y (test items 19-24): a genuinely REAL_TRAINED
    // head — real frozen encoder, real embeddings, real training — must
    // still never be promoted to authoritative production while its
    // calibration remains PENDING. ---

    @Test
    fun `a REAL_TRAINED head is never accepted as primary, even with a perfectly matching contract`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        val realTrained = export(qualification = ArtifactQualification.REAL_TRAINED)
        assertFalse(realTrained.isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `REAL_TRAINED is distinct from SYNTHETIC_SELFTEST and TRAINING_PENDING as a value, though all three reject`() {
        assertFalse(ArtifactQualification.REAL_TRAINED == ArtifactQualification.SYNTHETIC_SELFTEST)
        assertFalse(ArtifactQualification.REAL_TRAINED == ArtifactQualification.TRAINING_PENDING)
        assertFalse(ArtifactQualification.REAL_TRAINED.productionEligible)
    }

    @Test
    fun `only PRODUCTION_ELIGIBLE is ever productionEligible`() {
        assertTrue(ArtifactQualification.PRODUCTION_ELIGIBLE.productionEligible)
        for (other in ArtifactQualification.entries) {
            if (other == ArtifactQualification.PRODUCTION_ELIGIBLE) continue
            assertFalse(other.productionEligible, "expected $other to never be productionEligible")
        }
    }

    @Test
    fun `default calibrationStatus for a bare export is PENDING`() {
        val bareExport = LearnedHeadExport(schemaVersion = 1, embeddingDim = 4, intent = head, domain = head)
        assertTrue(bareExport.calibrationStatus == CalibrationStatus.PENDING)
    }

    @Test
    fun `a real-but-uncalibrated head is not marked fully production-qualified even when calibrationStatus is explicitly PENDING`() {
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        val realUncalibrated = LearnedHeadExport(
            schemaVersion = 1, embeddingDim = 768, intent = head, domain = head,
            encoderContract = SemanticEncoderContract.CURRENT,
            artifactQualification = ArtifactQualification.REAL_TRAINED,
            calibrationStatus = CalibrationStatus.PENDING,
        )
        assertFalse(realUncalibrated.isCompatibleWithRuntime(runtimeContract))
    }

    @Test
    fun `provenance fields round-trip through the data class untouched`() {
        val withProvenance = LearnedHeadExport(
            schemaVersion = 1, embeddingDim = 4, intent = head, domain = head,
            artifactQualification = ArtifactQualification.REAL_TRAINED,
            calibrationStatus = CalibrationStatus.PENDING,
            datasetRevision = "abc123",
            trainingSeed = 0,
            trainedAtIso = "2026-09-09T00:00:00Z",
        )
        assertTrue(withProvenance.datasetRevision == "abc123")
        assertTrue(withProvenance.trainingSeed == 0)
        assertTrue(withProvenance.trainedAtIso == "2026-09-09T00:00:00Z")
    }

    @Test
    fun `a PRODUCTION_ELIGIBLE head produced by a future calibration re-export is accepted`() {
        // § simulates what PASSAGGIO 15 is expected to do: re-export the SAME
        // artifact identity with qualification flipped to PRODUCTION_ELIGIBLE
        // and calibrationStatus flipped to CALIBRATED — never a new field
        // this pass needs to invent a check for, since isCompatibleWithRuntime
        // already gates purely on artifactQualification/contract/dimension.
        val runtimeContract = SemanticEncoderContract.CURRENT.copy(embeddingDimension = 768)
        val calibrated = export(qualification = ArtifactQualification.PRODUCTION_ELIGIBLE).copy(
            calibrationStatus = CalibrationStatus.CALIBRATED,
        )
        assertTrue(calibrated.isCompatibleWithRuntime(runtimeContract))
    }
}
