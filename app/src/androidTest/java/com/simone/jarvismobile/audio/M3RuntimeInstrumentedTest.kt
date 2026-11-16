package com.simone.jarvismobile.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.background.AssistantTaskWorker
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.EvidenceStrength
import com.simone.jarvismobile.core.knowledge.KnowledgeChunk
import com.simone.jarvismobile.core.knowledge.ScoredChunk
import com.simone.jarvismobile.core.llm.LoadGuard
import com.simone.jarvismobile.core.llm.QualificationState
import com.simone.jarvismobile.core.net.Decision
import com.simone.jarvismobile.core.net.OfflineMode
import com.simone.jarvismobile.llm.ModelLoadGuard
import com.simone.jarvismobile.llm.ModelProfileStore
import com.simone.jarvismobile.net.OfflineGate
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M3 — the REAL SessionCoordinator (production Hilt graph; only the model call and the retrieval are replaced through [ChatTestSeam]) under
 * STRICT_OFFLINE, plus the REAL [ModelProfileStore] and [ModelLoadGuard] on tiny FAKE model files (no weights, no LiteRT-LM engine is created):
 * this proves the profile state machine, the interrupted-load marker and the content-free trace wiring on Android — NOT that any model loads or answers.
 */
@RunWith(AndroidJUnit4::class)
class M3RuntimeInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var coordinator: SessionCoordinator
    private val tmp = ArrayList<File>()
    private lateinit var persistedMode: OfflineMode

    private val doc = KnowledgeChunk("zim:portogallo", "Portogallo", "", "Il Portogallo è uno Stato dell'Europa. La capitale è Lisbona.", sourceKind = "zim", collection = "Wikipedia (it)", lead = true)
    private val evidence = Evidence.Grounded(listOf(ScoredChunk(doc, 1.0, doc.text, false)), EvidenceStrength.STRONG, titleMatched = true)

    @Before fun setUp() {
        coordinator = EntryPointAccessors.fromApplication(ctx.applicationContext, AssistantTaskWorker.WorkerEntryPoint::class.java).coordinator()
        coordinator.newConversation(); OfflineGate.trace.clear()
        // the persisted offline mode of THIS installation: tests only change the in-process gate, and put it back to what is persisted
        persistedMode = runBlocking { EntryPointAccessors.fromApplication(ctx.applicationContext, com.simone.jarvismobile.net.OfflineEntryPoint::class.java).settings().offlineMode.first() }
    }

    @After fun tearDown() {
        coordinator.testSeam = null; coordinator.newConversation(); OfflineGate.initialise(persistedMode)
        tmp.forEach { it.delete() }
    }

    @Test fun aChatTurnWorksUnderStrictOfflineWithoutAnyNetworkDecisionAndTracesEstimateWithoutMeasurement() {
        OfflineGate.initialise(OfflineMode.STRICT_OFFLINE)
        coordinator.testSeam = ChatTestSeam(chat = { "La capitale del Portogallo è Lisbona." }, evidence = evidence)
        val reply = runBlocking { withTimeout(90_000) { coordinator.processQueuedText("m3-${System.nanoTime()}", "Qual è la capitale del Portogallo?") } }
        assertNotNull(reply); assertTrue(reply!!, "Lisbona" in reply)
        val ev = OfflineGate.trace.events()
        assertFalse("no network operation was allowed during the turn", ev.any { it.component.startsWith("net:") && it.decision == Decision.ALLOW.name })
        val tokens = ev.last { it.component == "llm:tokens" }
        assertTrue(tokens.numbers.containsKey("estimatedTokens"))
        assertFalse("the seam has no runtime: nothing may be reported as measured", tokens.numbers.containsKey("measuredTokens"))
        val json = OfflineGate.trace.preview().json
        assertFalse("the trace holds no question or answer text", "Portogallo" in json || "Lisbona" in json)
    }

    private fun store(): ModelProfileStore {
        val f = File(ctx.cacheDir, "profiles-${System.nanoTime()}.json").also { tmp += it }
        return ModelProfileStore(ctx, ModelLoadGuard(ctx)).also { it.file = f }
    }

    private fun fake(name: String, header: ByteArray): File = File(ctx.cacheDir, name).also { it.writeBytes(header + ByteArray(2048)); tmp += it }

    @Test fun profileLevelsFollowRealEventsAndAGgufIsNeverCompatible() {
        val s = store()
        val ok = fake("m3-ok.litertlm", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        s.noteLoad(ok.absolutePath, "fast", true, null, 1200, 4096, 100L shl 20, 700L shl 20, 8L shl 30)
        assertEquals(QualificationState.RUNTIME_LOADED, s.all().single().state)
        s.noteInference(ok.absolutePath, true, 800)
        assertEquals(QualificationState.INFERENCE_TESTED, s.all().single().state)
        val gguf = fake("m3-q.litertlm", byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0))
        s.noteInference(gguf.absolutePath, true, 5) // an "answer" for a file that never loaded proves nothing
        assertEquals(QualificationState.FAILED, s.all().first { it.name == gguf.name }.state)
        // a reopened store sees the persisted profile
        val again = ModelProfileStore(ctx, ModelLoadGuard(ctx)).also { it.file = s.file }
        assertEquals(2, again.all().size)
        assertTrue("no path in the file", !s.file.readText().contains(ctx.cacheDir.absolutePath))
    }

    @Test fun anInterruptedLoadMarkerBecomesAFailedProfileWithoutDeletingTheFile() {
        val guard = ModelLoadGuard(ctx)
        val f = fake("m3-crash.litertlm", ByteArray(8) { 9 })
        guard.guard.begin(LoadGuard.Attempt("fast", f.absolutePath, "m3", f.length(), System.currentTimeMillis(), "INSUFFICIENT")) // begun, never finished: process "killed"
        try {
            val restarted = ModelLoadGuard(ctx).also { it.startup() } // what the next process start does
            assertTrue(restarted.interrupted.value.any { it.path == f.absolutePath })
            val s = ModelProfileStore(ctx, restarted).also { it.file = File(ctx.cacheDir, "profiles-i-${System.nanoTime()}.json").also { x -> tmp += x } }
            s.noteLoad(f.absolutePath, "fast", true, null, 1, 4096, -1, -1, -1) // registers the profile
            s.syncInterrupted(); s.syncInterrupted() // idempotent
            val p = s.all().single()
            assertEquals(1, p.interruptedLoads); assertEquals(QualificationState.FAILED, p.state)
            assertTrue("the model file is untouched", f.isFile && f.length() > 0)
            assertFalse("automatic loading stays blocked after a crash", restarted.guard.autoLoadAllowed(f.absolutePath))
        } finally { guard.guard.dismiss(f.absolutePath) }
    }

    @Test fun aFileReplacedUnderTheSameNameAndSizeLosesItsProofsAndAQualificationNeedsTheExactFile() {
        val s = store()
        val f = fake("m3-same.litertlm", ByteArray(8) { 1 })
        s.noteLoad(f.absolutePath, "fast", true, null, 5, 4096, -1, -1, -1); s.noteInference(f.absolutePath, true, 5)
        assertEquals(QualificationState.INFERENCE_TESTED, s.all().single().state)
        // same name, same size, different content (and a new modification time)
        val size = f.length(); f.writeBytes(ByteArray(size.toInt()) { 7 }); f.setLastModified(f.lastModified() + 5_000)
        s.noteInference(f.absolutePath, true, 5) // looks at the file again
        val p = s.all().single()
        assertEquals("a replaced file is not the tested artifact", QualificationState.STATICALLY_COMPATIBLE, p.state)
        assertTrue(p.sha256 == null && p.qualification == null)
        assertFalse(s.isQualifiedNow(f.absolutePath))
        // hashing is on demand, and the hash is only claimed for a file that did not change while it was read
        val h = s.computeSha256(f.absolutePath)
        assertNotNull(h); assertEquals(64, h!!.length)
        assertNotNull(s.all().single().sha256)
    }

    @Test fun theRegistryIsWrittenWithoutLosingTheLastGoodVersion() {
        val s = store()
        val f = fake("m3-p.litertlm", ByteArray(8) { 3 })
        s.noteLoad(f.absolutePath, "fast", true, null, 5, 4096, -1, -1, -1)
        val firstGood = s.file.readText()
        s.noteInference(f.absolutePath, true, 5)
        val second = s.file.readText()
        assertTrue(second != firstGood)
        assertEquals("the previous good version is kept as .bak", firstGood, File(s.file.path + ".bak").readText())
        File(s.file.path + ".tmp").writeText("{torn") // leftover of an interrupted write
        val reopened = ModelProfileStore(ctx, ModelLoadGuard(ctx)).also { it.file = s.file }
        assertEquals(QualificationState.INFERENCE_TESTED, reopened.all().single().state)
        assertFalse(reopened.saveFailing)
        tmp += File(s.file.path + ".bak"); tmp += File(s.file.path + ".tmp")
    }
}
