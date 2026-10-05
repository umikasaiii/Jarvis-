package com.simone.jarvismobile.core.voice.silero

import kotlinx.serialization.Serializable

/** Typed failure reasons for the Silero VAD import / load / inference path. Never free-form policy strings. */
enum class SileroFailure {
    NOT_IMPORTED,
    SHA_MISMATCH,
    SIZE_MISMATCH,
    ORT_LOAD_FAILED,
    GRAPH_MISMATCH,
    UNSUPPORTED_INPUT,
    INVALID_OUTPUT,
    INFERENCE_FAILED,
    MODEL_CLOSED,
    UNREADABLE_FILE,
    STORAGE_FAILED,
}

/** Bounded model lifecycle. */
enum class SileroModelState { NOT_IMPORTED, LOADING, READY, INCOMPATIBLE, ERROR }

/**
 * Canonical identity + streaming contract of the one Silero VAD model JARVIS knows (Live Voice Phase 0.8).
 * The model binary is NEVER committed or bundled: the user imports it, and it is accepted only if its
 * size, SHA-256 and ONNX graph all match this contract. Identity is model id + SHA-256, never the file name.
 * The committed JSON twin (app/src/main/assets/model_manifests/silero_vad.json) is pinned equal to
 * [CANONICAL] by a test; the Kotlin constants stay authoritative for tensor correctness.
 */
@Serializable
data class SileroModelManifest(
    val modelId: String,
    val displayName: String,
    val filename: String,
    val expectedSha256: String,
    val expectedSizeBytes: Long,
    val sampleRateHz: Int,
    val frameSamples: Int,
    val contextSamples: Int,
    val effectiveInputSamples: Int,
    val stateShape: List<Int>,
    val license: String,
    val upstreamProject: String,
    val modelRevision: String,
) {
    companion object {
        const val FRAME_SAMPLES = 512
        const val CONTEXT_SAMPLES = 64
        const val EFFECTIVE_INPUT_SAMPLES = CONTEXT_SAMPLES + FRAME_SAMPLES
        const val SAMPLE_RATE_HZ = 16_000
        const val STATE_LAYERS = 2
        const val STATE_HIDDEN = 128
        const val STATE_FLOATS = STATE_LAYERS * STATE_HIDDEN // batch = 1

        val CANONICAL = SileroModelManifest(
            modelId = "silero_vad_onnx_v5",
            displayName = "Silero VAD",
            filename = "silero_vad.onnx",
            expectedSha256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3",
            expectedSizeBytes = 2_327_524L,
            sampleRateHz = SAMPLE_RATE_HZ,
            frameSamples = FRAME_SAMPLES,
            contextSamples = CONTEXT_SAMPLES,
            effectiveInputSamples = EFFECTIVE_INPUT_SAMPLES,
            stateShape = listOf(STATE_LAYERS, 1, STATE_HIDDEN),
            license = "MIT",
            upstreamProject = "snakers4/silero-vad",
            modelRevision = "upstream commit not determined; identity pinned by SHA-256 (audited 2026-10-05)",
        )
    }
}

/**
 * Qualification status of the speech threshold. The upstream reference value is a REFERENCE DEFAULT,
 * not declared optimal for JARVIS: the device-qualification pass decides.
 */
data class SileroVadConfig(val speechThreshold: Float = REFERENCE_SPEECH_THRESHOLD) {
    init {
        require(!speechThreshold.isNaN() && speechThreshold in 0f..1f) { "speechThreshold out of 0..1" }
    }

    companion object {
        const val REFERENCE_SPEECH_THRESHOLD = 0.5f
        const val THRESHOLD_STATUS = "REFERENCE_DEFAULT_DEVICE_UNQUALIFIED"
    }
}

enum class SileroElementType { FLOAT32, INT64, OTHER }

/** One graph tensor as reported by the runtime. Dynamic dimensions are -1. */
data class SileroTensorDescriptor(val name: String, val elementType: SileroElementType, val shape: List<Long>)

data class SileroGraphDescriptor(val inputs: List<SileroTensorDescriptor>, val outputs: List<SileroTensorDescriptor>)

/** Verifies the real graph of an imported file against the measured contract. The binary stays authoritative. */
object SileroGraphContract {
    const val INPUT = "input"
    const val STATE = "state"
    const val SR = "sr"
    const val OUTPUT = "output"
    const val STATE_N = "stateN"

    /** @return null when the graph matches, [SileroFailure.GRAPH_MISMATCH] otherwise. */
    fun verify(graph: SileroGraphDescriptor): SileroFailure? {
        if (graph.inputs.map { it.name }.toSet() != setOf(INPUT, STATE, SR) || graph.inputs.size != 3) return SileroFailure.GRAPH_MISMATCH
        if (graph.outputs.map { it.name }.toSet() != setOf(OUTPUT, STATE_N) || graph.outputs.size != 2) return SileroFailure.GRAPH_MISMATCH
        val input = graph.inputs.first { it.name == INPUT }
        val state = graph.inputs.first { it.name == STATE }
        val sr = graph.inputs.first { it.name == SR }
        val output = graph.outputs.first { it.name == OUTPUT }
        val stateN = graph.outputs.first { it.name == STATE_N }

        fun dimOk(actual: Long, vararg allowed: Long) = actual == -1L || actual in allowed
        val ok = input.elementType == SileroElementType.FLOAT32 && input.shape.size == 2 &&
            dimOk(input.shape[0], 1) && dimOk(input.shape[1], SileroModelManifest.EFFECTIVE_INPUT_SAMPLES.toLong()) &&
            state.elementType == SileroElementType.FLOAT32 && state.shape.size == 3 &&
            state.shape[0] == SileroModelManifest.STATE_LAYERS.toLong() && dimOk(state.shape[1], 1) &&
            state.shape[2] == SileroModelManifest.STATE_HIDDEN.toLong() &&
            sr.elementType == SileroElementType.INT64 && sr.shape.isEmpty() &&
            output.elementType == SileroElementType.FLOAT32 && output.shape.size == 2 &&
            dimOk(output.shape[0], 1) && dimOk(output.shape[1], 1) &&
            stateN.elementType == SileroElementType.FLOAT32 && stateN.shape.size == 3
        return if (ok) null else SileroFailure.GRAPH_MISMATCH
    }
}

/** Size + SHA-256 acceptance of an imported file (the pure half of the import validation). */
object SileroImportValidator {
    fun verifyIdentity(sizeBytes: Long, sha256Hex: String, manifest: SileroModelManifest = SileroModelManifest.CANONICAL): SileroFailure? = when {
        sizeBytes != manifest.expectedSizeBytes -> SileroFailure.SIZE_MISMATCH
        !sha256Hex.equals(manifest.expectedSha256, ignoreCase = true) -> SileroFailure.SHA_MISMATCH
        else -> null
    }
}
