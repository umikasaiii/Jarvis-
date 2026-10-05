package com.simone.jarvismobile.audio.silero

import com.simone.jarvismobile.core.voice.silero.SileroElementType
import com.simone.jarvismobile.core.voice.silero.SileroFailure
import com.simone.jarvismobile.core.voice.silero.SileroGraphDescriptor
import com.simone.jarvismobile.core.voice.silero.SileroInferenceBackend
import com.simone.jarvismobile.core.voice.silero.SileroInferenceResult
import com.simone.jarvismobile.core.voice.silero.SileroModelManifest
import com.simone.jarvismobile.core.voice.silero.SileroModelState
import com.simone.jarvismobile.core.voice.silero.SileroTensorDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class SileroVadModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(2_000) { it.toByte() }
    private val manifest = SileroModelManifest.CANONICAL.copy(
        expectedSha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        expectedSizeBytes = payload.size.toLong(),
    )
    private val graph = SileroGraphDescriptor(
        listOf(
            SileroTensorDescriptor("input", SileroElementType.FLOAT32, listOf(-1, -1)),
            SileroTensorDescriptor("state", SileroElementType.FLOAT32, listOf(2, -1, 128)),
            SileroTensorDescriptor("sr", SileroElementType.INT64, emptyList()),
        ),
        listOf(SileroTensorDescriptor("output", SileroElementType.FLOAT32, listOf(-1, 1)), SileroTensorDescriptor("stateN", SileroElementType.FLOAT32, listOf(-1, -1, -1))),
    )

    private class CountingBackend(val log: MutableList<String>) : SileroInferenceBackend {
        override fun run(input: FloatArray, state: FloatArray, sampleRateHz: Long) = SileroInferenceResult.Ok(0.1f, FloatArray(256))
        override fun close() { log += "close" }
    }

    private val log = mutableListOf<String>()
    private var opens = 0
    private var openResult: SileroBackendOpen? = null

    private fun manager(store: SileroModelStore = SileroModelStore(tmp.root, manifest)) = SileroVadModelManager(
        store = store,
        inspector = SileroGraphInspector { SileroGraphInspection.Ok(graph) },
        backendFactory = SileroBackendFactory { opens++; openResult ?: SileroBackendOpen.Ok(CountingBackend(log)) },
        io = Dispatchers.Unconfined,
    )

    private fun import(m: SileroVadModelManager) = runBlocking { m.import(ByteArrayInputStream(payload)) }

    @Test fun lifecycleNotImportedThenReady() = runBlocking {
        val m = manager()
        m.refresh()
        assertEquals(SileroModelState.NOT_IMPORTED, m.status.value.state)
        assertNull(m.ensureLoaded())
        assertTrue(import(m) is SileroImportOutcome.Ok)
        assertEquals(SileroModelState.READY, m.status.value.state)
        assertTrue(m.status.value.shaVerified)
    }

    @Test fun engineIsLoadedOnceAndReused() = runBlocking {
        val m = manager(); import(m)
        val a = m.ensureLoaded(); val b = m.ensureLoaded()
        assertNotNull(a); assertSame(a, b)
        assertEquals(1, opens)
    }

    @Test fun removeClosesEngineBeforeDeletingThenNotImported() = runBlocking {
        val store = SileroModelStore(tmp.root, manifest)
        val m = manager(store); import(m); m.ensureLoaded()
        assertTrue(m.remove())
        assertEquals(listOf("close"), log) // closed exactly once
        assertFalse(store.isInstalled())
        assertEquals(SileroModelState.NOT_IMPORTED, m.status.value.state)
        assertNull(m.ensureLoaded())
    }

    @Test fun incompatibleRuntimeLoadIsReportedNotThrown() = runBlocking {
        val m = manager(); import(m)
        openResult = SileroBackendOpen.Failed(SileroFailure.ORT_LOAD_FAILED)
        assertNull(m.ensureLoaded())
        assertEquals(SileroModelState.INCOMPATIBLE, m.status.value.state)
        assertEquals(SileroFailure.ORT_LOAD_FAILED, m.status.value.failure)
    }

    @Test fun corruptedInstalledFileIsAnErrorAndNeverLoaded() = runBlocking {
        val store = SileroModelStore(tmp.root, manifest)
        val m = manager(store); import(m)
        store.modelFile.writeBytes(ByteArray(payload.size))
        assertNull(m.ensureLoaded())
        assertEquals(SileroModelState.ERROR, m.status.value.state)
        assertEquals(SileroFailure.SHA_MISMATCH, m.status.value.failure)
        assertEquals(0, opens)
    }

    @Test fun replacementClosesOldEngineAndReloadsLazily() = runBlocking {
        val m = manager(); import(m); val first = m.ensureLoaded()
        assertTrue(import(m) is SileroImportOutcome.Ok)
        assertEquals(listOf("close"), log)
        val second = m.ensureLoaded()
        assertNotNull(second); assertFalse(first === second); assertEquals(2, opens)
    }

    @Test fun rejectedImportKeepsEngineAndStatus() = runBlocking {
        val m = manager(); import(m); val e = m.ensureLoaded()
        val r = m.import(ByteArrayInputStream(ByteArray(10)))
        assertTrue(r is SileroImportOutcome.Rejected)
        assertSame(e, m.ensureLoaded()); assertTrue(log.isEmpty())
        assertEquals(SileroModelState.READY, m.status.value.state)
    }
}
