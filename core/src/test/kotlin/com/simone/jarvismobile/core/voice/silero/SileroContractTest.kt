package com.simone.jarvismobile.core.voice.silero

import com.simone.jarvismobile.core.voice.VadTurnConfig
import com.simone.jarvismobile.core.voice.VoicePcmFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun t(name: String, type: SileroElementType, vararg shape: Long) = SileroTensorDescriptor(name, type, shape.toList())

private fun measuredGraph() = SileroGraphDescriptor(
    inputs = listOf(
        t("input", SileroElementType.FLOAT32, -1, -1),
        t("state", SileroElementType.FLOAT32, 2, -1, 128),
        t("sr", SileroElementType.INT64),
    ),
    outputs = listOf(
        t("output", SileroElementType.FLOAT32, -1, 1),
        t("stateN", SileroElementType.FLOAT32, -1, -1, -1),
    ),
)

class SileroContractTest {
    @Test fun measuredGraphMatches() = assertNull(SileroGraphContract.verify(measuredGraph()))

    @Test fun differentTensorNamesAreRejected() {
        val g = measuredGraph().let { it.copy(inputs = it.inputs.map { i -> if (i.name == "sr") i.copy(name = "rate") else i }) }
        assertEquals(SileroFailure.GRAPH_MISMATCH, SileroGraphContract.verify(g))
    }

    @Test fun incompatibleShapesOrTypesAreRejected() {
        val base = measuredGraph()
        val badState = base.copy(inputs = base.inputs.map { if (it.name == "state") it.copy(shape = listOf(2, -1, 64)) else it })
        val badSr = base.copy(inputs = base.inputs.map { if (it.name == "sr") it.copy(elementType = SileroElementType.FLOAT32) else it })
        val badOut = base.copy(outputs = base.outputs.map { if (it.name == "output") it.copy(shape = listOf(-1, 2)) else it })
        val extra = base.copy(inputs = base.inputs + t("extra", SileroElementType.FLOAT32, 1))
        val fixedLen = base.copy(inputs = base.inputs.map { if (it.name == "input") it.copy(shape = listOf(1, 512)) else it })
        for (g in listOf(badState, badSr, badOut, extra, fixedLen)) assertEquals(SileroFailure.GRAPH_MISMATCH, SileroGraphContract.verify(g))
        val ok576 = base.copy(inputs = base.inputs.map { if (it.name == "input") it.copy(shape = listOf(1, 576)) else it })
        assertNull(SileroGraphContract.verify(ok576))
    }

    @Test fun identityRequiresExactSizeAndSha() {
        val m = SileroModelManifest.CANONICAL
        assertNull(SileroImportValidator.verifyIdentity(m.expectedSizeBytes, m.expectedSha256.uppercase()))
        assertEquals(SileroFailure.SIZE_MISMATCH, SileroImportValidator.verifyIdentity(m.expectedSizeBytes + 1, m.expectedSha256))
        assertEquals(SileroFailure.SHA_MISMATCH, SileroImportValidator.verifyIdentity(m.expectedSizeBytes, "0".repeat(64)))
    }

    @Test fun contextArithmeticIsTheDocumentedOne() {
        assertEquals(576, SileroModelManifest.EFFECTIVE_INPUT_SAMPLES)
        assertEquals(256, SileroModelManifest.STATE_FLOATS)
        assertEquals(512, SileroModelManifest.FRAME_SAMPLES)
    }

    @Test fun committedJsonManifestIsPinnedEqualToTheTypedContract() {
        val file = listOf(
            File("../app/src/main/assets/model_manifests/silero_vad.json"),
            File("app/src/main/assets/model_manifests/silero_vad.json"),
        ).firstOrNull { it.isFile } ?: error("manifest JSON not found from ${File(".").absolutePath}")
        val decoded = Json.decodeFromString(SileroModelManifest.serializer(), file.readText())
        assertEquals(SileroModelManifest.CANONICAL, decoded)
    }

    @Test fun thresholdIsExplicitAndMarkedUnqualified() {
        assertEquals(0.5f, SileroVadConfig().speechThreshold)
        assertTrue(SileroVadConfig.THRESHOLD_STATUS.contains("UNQUALIFIED"))
        assertEquals(3, VadTurnConfig.PLACEHOLDER_UNQUALIFIED.minSpeechFrames)
    }
}

class SileroQualificationTest {
    private class Scripted(val p: List<Float>) : SileroInferenceBackend {
        var i = 0
        override fun run(input: FloatArray, state: FloatArray, sampleRateHz: Long) =
            SileroInferenceResult.Ok(p[i++ % p.size], FloatArray(256))
        override fun close() {}
    }

    private fun frame(seq: Long) = VoicePcmFrame.copyOf(ShortArray(512) { 10 }, 512, 16_000, seq)!!

    @Test fun sessionCountsFramesAndTurnEventsWithoutPcmOrPerFrameLists() = runBlocking {
        // 4 speech, 10 silence => with placeholder (3,8): one start, one end.
        val probs = List(4) { 0.9f } + List(10) { 0.01f }
        val engine = SileroVadEngine(Scripted(probs))
        val session = SileroQualificationSession(engine)
        probs.indices.forEach { session.onFrame(frame(it.toLong())) }
        val r = session.result(captureFramesDropped = 2, captureFailure = null)
        assertEquals(14, r.framesProcessed)
        assertEquals(4, r.framesSpeech); assertEquals(10, r.framesNonSpeech); assertEquals(0, r.framesUnknown)
        assertEquals(1, r.speechStartedCount); assertEquals(1, r.speechEndedCount)
        assertEquals(2, r.captureFramesDropped)
        assertEquals(0.9f, r.maxProbability)
        assertTrue(r.thresholdStatus.contains("UNQUALIFIED"))
        val types = SileroQualificationResult::class.java.declaredFields.map { it.type }
        assertTrue(types.none { it.isArray || Collection::class.java.isAssignableFrom(it) })
    }

    @Test fun sessionStartResetsEngineCounters() = runBlocking {
        val engine = SileroVadEngine(Scripted(listOf(0.9f)))
        engine.process(frame(0))
        SileroQualificationSession(engine)
        assertEquals(SileroEngineStats(), engine.stats())
    }

    @Test fun gateRequiresProvablyFreeMicrophone() {
        fun g(ready: Boolean = true, idle: Boolean = true, wakeOn: Boolean = false, wakeLive: Boolean = false, tr: Boolean = false, cap: Boolean = false) =
            SileroQualificationGate.check(ready, idle, wakeOn, wakeLive, tr, cap)
        assertEquals(SileroQualificationAvailability.AVAILABLE, g())
        assertEquals(SileroQualificationAvailability.MODEL_NOT_READY, g(ready = false))
        assertEquals(SileroQualificationAvailability.CONVERSATION_ACTIVE, g(idle = false))
        assertEquals(SileroQualificationAvailability.WAKE_WORD_LISTENING, g(wakeOn = true, wakeLive = true))
        assertEquals(SileroQualificationAvailability.WAKE_WORD_ENABLED, g(wakeOn = true))
        assertEquals(SileroQualificationAvailability.TRANSLATOR_ACTIVE, g(tr = true))
        assertEquals(SileroQualificationAvailability.CAPTURE_ACTIVE, g(cap = true))
    }
}
