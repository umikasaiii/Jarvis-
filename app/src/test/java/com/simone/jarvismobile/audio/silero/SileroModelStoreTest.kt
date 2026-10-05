package com.simone.jarvismobile.audio.silero

import com.simone.jarvismobile.core.voice.silero.SileroElementType
import com.simone.jarvismobile.core.voice.silero.SileroFailure
import com.simone.jarvismobile.core.voice.silero.SileroGraphDescriptor
import com.simone.jarvismobile.core.voice.silero.SileroModelManifest
import com.simone.jarvismobile.core.voice.silero.SileroTensorDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Deterministic import tests: the real ONNX binary is never needed (a synthetic file + a test manifest pin). */
class SileroModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(5_000) { (it * 31).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val manifest = SileroModelManifest.CANONICAL.copy(expectedSha256 = sha(payload), expectedSizeBytes = payload.size.toLong())

    private val goodGraph = SileroGraphDescriptor(
        listOf(
            SileroTensorDescriptor("input", SileroElementType.FLOAT32, listOf(-1, -1)),
            SileroTensorDescriptor("state", SileroElementType.FLOAT32, listOf(2, -1, 128)),
            SileroTensorDescriptor("sr", SileroElementType.INT64, emptyList()),
        ),
        listOf(
            SileroTensorDescriptor("output", SileroElementType.FLOAT32, listOf(-1, 1)),
            SileroTensorDescriptor("stateN", SileroElementType.FLOAT32, listOf(-1, -1, -1)),
        ),
    )
    private val okInspector = SileroGraphInspector { SileroGraphInspection.Ok(goodGraph) }

    private fun store() = SileroModelStore(tmp.root, manifest)
    private fun tmpLeftovers() = java.io.File(tmp.root, "silero_vad/tmp").listFiles()?.size ?: 0

    @Test fun correctShaAndSizeAreAcceptedAndPromoted() {
        val s = store()
        val r = s.import(ByteArrayInputStream(payload), okInspector)
        assertTrue(r is SileroImportOutcome.Ok)
        assertArrayEquals(payload, s.modelFile.readBytes())
        assertTrue(s.isInstalled()); assertEquals(null, s.verifyInstalled())
        assertEquals(0, tmpLeftovers())
        assertTrue(s.modelFile.absolutePath.startsWith(tmp.root.absolutePath)) // app-private root only
    }

    @Test fun wrongShaIsRejectedAndTempCleaned() {
        val bad = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val r = store().import(ByteArrayInputStream(bad), okInspector)
        assertEquals(SileroImportOutcome.Rejected(SileroFailure.SHA_MISMATCH), r)
        assertFalse(store().isInstalled()); assertEquals(0, tmpLeftovers())
    }

    @Test fun wrongSizeIsRejected() {
        assertEquals(SileroImportOutcome.Rejected(SileroFailure.SIZE_MISMATCH), store().import(ByteArrayInputStream(payload.copyOf(100)), okInspector))
        assertEquals(SileroImportOutcome.Rejected(SileroFailure.SIZE_MISMATCH), store().import(ByteArrayInputStream(payload + ByteArray(50)), okInspector))
        assertEquals(0, tmpLeftovers())
    }

    @Test fun oversizedSourceIsCappedNotFullyCopied() {
        var read = 0L
        val huge = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        assertEquals(SileroImportOutcome.Rejected(SileroFailure.SIZE_MISMATCH), store().import(huge, okInspector))
        assertTrue("copied far too much: $read", read < payload.size + 200_000)
    }

    @Test fun graphMismatchRejectedAndNothingPromoted() {
        val bad = SileroGraphInspector { SileroGraphInspection.Ok(goodGraph.copy(inputs = goodGraph.inputs.drop(1))) }
        assertEquals(SileroImportOutcome.Rejected(SileroFailure.GRAPH_MISMATCH), store().import(ByteArrayInputStream(payload), bad))
        assertFalse(store().isInstalled()); assertEquals(0, tmpLeftovers())
    }

    @Test fun ortLoadFailureIsTyped() {
        val fail = SileroGraphInspector { SileroGraphInspection.Failed(SileroFailure.ORT_LOAD_FAILED) }
        assertEquals(SileroImportOutcome.Rejected(SileroFailure.ORT_LOAD_FAILED), store().import(ByteArrayInputStream(payload), fail))
    }

    @Test fun unreadableSourceIsTypedAndCleaned() {
        val broken = object : InputStream() { override fun read(): Int = throw IOException("x") }
        assertEquals(SileroImportOutcome.Rejected(SileroFailure.UNREADABLE_FILE), store().import(broken, okInspector))
        assertEquals(0, tmpLeftovers())
    }

    @Test fun previousGoodModelSurvivesAFailedReplacement() {
        val s = store()
        s.import(ByteArrayInputStream(payload), okInspector)
        val before = s.modelFile.readBytes()
        val bad = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(s.import(ByteArrayInputStream(bad), okInspector) is SileroImportOutcome.Rejected)
        assertTrue(s.import(ByteArrayInputStream(payload), SileroGraphInspector { SileroGraphInspection.Failed(SileroFailure.GRAPH_MISMATCH) }) is SileroImportOutcome.Rejected)
        assertArrayEquals(before, s.modelFile.readBytes()); assertEquals(null, s.verifyInstalled()); assertEquals(0, tmpLeftovers())
    }

    @Test fun validReplacementIsAtomicallyPromoted() {
        val s = store()
        s.import(ByteArrayInputStream(payload), okInspector)
        assertTrue(s.import(ByteArrayInputStream(payload), okInspector) is SileroImportOutcome.Ok)
        assertEquals(null, s.verifyInstalled()); assertEquals(0, tmpLeftovers())
    }

    @Test fun removeDeletesAndStatusAfterIsNotImported() {
        val s = store()
        s.import(ByteArrayInputStream(payload), okInspector)
        assertTrue(s.remove())
        assertFalse(s.isInstalled()); assertEquals(SileroFailure.NOT_IMPORTED, s.verifyInstalled())
    }

    @Test fun onDiskCorruptionIsDetected() {
        val s = store()
        s.import(ByteArrayInputStream(payload), okInspector)
        s.modelFile.writeBytes(payload.copyOf().also { it[3] = 0 })
        assertEquals(SileroFailure.SHA_MISMATCH, s.verifyInstalled())
    }

    @Test fun staleTempFilesAreCleanedOnNextImport() {
        val dir = java.io.File(tmp.root, "silero_vad/tmp").also { it.mkdirs() }
        java.io.File(dir, "import-old.part").writeBytes(byteArrayOf(1))
        store().import(ByteArrayInputStream(payload), okInspector)
        assertEquals(0, tmpLeftovers())
    }
}
