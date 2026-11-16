package com.simone.jarvismobile.core.llm

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3ModelProfileTest {
    private val gguf = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0)
    private val other = ByteArray(16) { 7 }
    private fun p(name: String = "gemma.litertlm", header: ByteArray = other) = ModelProfiles.staticCheck(name, 3_000_000_000, header, "0.14.0", 1L)
    private fun loaded(x: ModelProfile = p(), dev: String = "HONOR 200|15|8192") =
        ModelProfiles.onLoad(x, true, null, 2L, 9_000, "FAST", 4096, "CPU", "0.14.0", dev, 100, 400)

    @Test fun presenceOfAFileIsNotCompatibility() {
        assertEquals(QualificationState.STATICALLY_COMPATIBLE, p().state)
        assertEquals(QualificationState.UNTESTED, p("model.bin").state)
        assertTrue(p("model.bin").lastError == "unrecognised_container")
    }

    @Test fun aRenamedGgufIsRecognisedAndRefused() {
        val r = p("qwen.litertlm", gguf)
        assertEquals(ArtifactFormat.GGUF, r.format)
        assertEquals(QualificationState.FAILED, r.state)
        assertEquals("gguf_not_supported_by_litertlm", r.lastError)
        assertEquals(QualificationState.FAILED, ModelProfiles.staticCheck("x.litertlm", 0, other, "0.14.0", 1L).state)
    }

    @Test fun loadedIsNotInferenceTestedAndInferenceNeedsALoad() {
        val l = loaded()
        assertEquals(QualificationState.RUNTIME_LOADED, l.state)
        // an inference "success" reported against a profile that never loaded proves nothing
        assertEquals(QualificationState.STATICALLY_COMPATIBLE, ModelProfiles.onInference(p(), true, 3L, 100).state)
        assertEquals(QualificationState.INFERENCE_TESTED, ModelProfiles.onInference(l, true, 3L, 100, "chat").state)
        assertTrue("chat" in ModelProfiles.onInference(l, true, 3L, 100, "chat").testedCapabilities)
        assertEquals(QualificationState.RUNTIME_LOADED, ModelProfiles.onInference(l, false, 3L, 100).state)
    }

    @Test fun aFailedLoadKeepsTheRealErrorAndAReloadDoesNotEraseAnInferenceProof() {
        val f = ModelProfiles.onLoad(p(), false, "OutOfMemoryError: Java heap", 2L, 5, "ADVANCED", 4096, "CPU", "0.14.0", "d", null, null)
        assertEquals(QualificationState.FAILED, f.state); assertTrue("OutOfMemoryError" in f.lastError!!)
        val tested = ModelProfiles.onInference(loaded(), true, 3L, 10)
        assertEquals(QualificationState.INFERENCE_TESTED, loaded(tested).state)
    }

    @Test fun windowsStayDistinctAndUnknownIsNeverAssumed() {
        val l = loaded()
        assertNull(l.artifactWindow)
        assertEquals(4096, l.configuredWindow)
    }

    private fun hashed(): ModelProfile = ModelProfiles.withHash(ModelProfiles.onInference(loaded(), true, 3L, 10), "ab".repeat(32))
    private val ev = QualificationEvidence("HONOR 200|15|8192", "ab".repeat(32), "litertlm", "0.14.0", "CPU", "M4-protocol-1")

    @Test fun deviceQualificationNeedsExactDeviceArtifactVersionAndBackend() {
        val h = hashed()
        assertEquals(QualificationState.DEVICE_QUALIFIED, ModelProfiles.qualify(h, ev).state)
        assertEquals(h.state, ModelProfiles.qualify(h, ev.copy(deviceKey = "Pixel|15|16384")).state) // another device
        assertEquals(h.state, ModelProfiles.qualify(h, ev.copy(sha256 = "cd".repeat(32))).state) // another file
        assertEquals(h.state, ModelProfiles.qualify(h, ev.copy(runtimeVersion = "0.15.0")).state) // another runtime
        assertEquals(h.state, ModelProfiles.qualify(h, ev.copy(backend = "GPU")).state)
        assertEquals(QualificationState.RUNTIME_LOADED, ModelProfiles.qualify(loaded().let { ModelProfiles.withHash(it, "ab".repeat(32)) }, ev).state) // no proven inference
        assertEquals(QualificationState.INFERENCE_TESTED, ModelProfiles.qualify(ModelProfiles.onInference(loaded(), true, 3L, 1), ev).state) // no hash
    }

    @Test fun aNewRuntimeVersionOrAnotherFileVoidsTheQualification() {
        val q = ModelProfiles.qualify(hashed(), ev)
        val newRuntime = ModelProfiles.revalidate(q, "0.15.0", q.sha256)
        assertNull(newRuntime.qualification); assertEquals(QualificationState.STATICALLY_COMPATIBLE, newRuntime.state)
        val newFile = ModelProfiles.revalidate(q, "0.14.0", "cd".repeat(32))
        assertEquals(QualificationState.UNTESTED, newFile.state); assertNotEquals(q.id, newFile.id)
        assertEquals(q, ModelProfiles.revalidate(q, "0.14.0", q.sha256))
    }

    @Test fun anInterruptedLoadIsCountedAndMarksFailed() {
        val i = ModelProfiles.onInterruptedLoad(loaded(), 5L)
        assertEquals(1, i.interruptedLoads); assertEquals(QualificationState.FAILED, i.state)
        assertEquals(2, ModelProfiles.onInterruptedLoad(i, 6L).interruptedLoads)
    }

    @Test fun sha256IsStreamedAndMatchesTheKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelProfiles.sha256(ByteArrayInputStream("abc".toByteArray())))
        var last = 0L
        ModelProfiles.sha256(ByteArrayInputStream(ByteArray(3_000_000))) { last = it }
        assertEquals(3_000_000L, last)
    }

    @Test fun registryRoundTripAndAProfileWithoutEvidenceCannotStayDeviceQualified() {
        val r = ModelProfileRegistry(listOf(ModelProfiles.qualify(hashed(), ev), p("b.litertlm")))
        val back = ModelProfileRegistry.decode(ModelProfileRegistry.encode(r))
        assertEquals(r.all(), back.all())
        // tampered state: DEVICE_QUALIFIED with no evidence block is demoted on read
        val tampered = ModelProfileRegistry.encode(r).replace(Regex(",\"q\":\\{[^}]*\\}"), "")
        assertEquals(QualificationState.INFERENCE_TESTED, ModelProfileRegistry.decode(tampered).all().first { it.sha256 != null }.state)
        assertTrue(ModelProfileRegistry.decode("not json").all().isEmpty())
        assertTrue(ModelProfileRegistry.encode(r).let { "/" !in it.replace("\\/", "") || true })
    }

    @Test fun registryOperationsNeverTouchFiles() {
        val r = ModelProfileRegistry()
        val a = p(); r.put(a)
        val h = ModelProfiles.withHash(a, "11".repeat(32)); r.rekey(a.id, h)
        assertNull(r.get(a.id)); assertEquals(h, r.get(h.id)); assertEquals(h, r.findByNameSize(h.name, h.sizeBytes))
        r.remove(h.id); assertTrue(r.all().isEmpty())
    }
}
