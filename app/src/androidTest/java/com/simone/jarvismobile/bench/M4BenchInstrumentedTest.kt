package com.simone.jarvismobile.bench

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.background.AssistantTaskWorker
import com.simone.jarvismobile.core.bench.BenchCase
import com.simone.jarvismobile.core.bench.BenchDataset
import com.simone.jarvismobile.core.bench.BenchReport
import com.simone.jarvismobile.core.bench.BenchRunner
import com.simone.jarvismobile.core.bench.BenchStore
import com.simone.jarvismobile.core.bench.Dim
import com.simone.jarvismobile.core.bench.PipelineControlExecutor
import com.simone.jarvismobile.core.bench.RunIdentity
import com.simone.jarvismobile.core.net.OfflineMode
import com.simone.jarvismobile.net.OfflineGate
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M4 — the benchmark infrastructure on Android (EMULATOR TESTED). What it proves: the bundled dataset/fixtures ship inside the APK and validate there; the
 * model-free pipeline control runs on Android's runtime; results persist in app-private storage and resume; the case runner drives the REAL SessionCoordinator
 * (only the model call is replaced through the existing seam: a PIPELINE CHECK, not a model result); the device probe returns real values or null.
 * What it does NOT prove: any model's answer quality, any physical measurement of the Honor 200, real archives.
 */
@RunWith(AndroidJUnit4::class)
class M4BenchInstrumentedTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dir = File(ctx.cacheDir, "m4-${System.nanoTime()}")
    private lateinit var persistedMode: OfflineMode
    private val loaded by lazy { BenchDataset.loadBundled() }

    @Before fun setUp() {
        persistedMode = runBlocking { EntryPointAccessors.fromApplication(ctx, com.simone.jarvismobile.net.OfflineEntryPoint::class.java).settings().offlineMode.first() }
    }

    @After fun tearDown() { OfflineGate.initialise(persistedMode); dir.deleteRecursively() }

    private fun identity(id: String, kind: String = "PIPELINE_CONTROL") = RunIdentity(runId = id, kind = kind, datasetFingerprint = BenchDataset.bundledFingerprint(), datasetVersion = loaded.first.version, fixturesVersion = loaded.second.version, environment = "emulator", startedAt = 1)

    @Test fun aStoredRunIsNeverResumedAsADifferentRunKindOrBuild() = runBlocking {
        val (d, f) = loaded
        val store = BenchStore(dir)
        val ex = PipelineControlExecutor(PipelineControlExecutor.chunkRetrieval(f), "faithful")
        BenchRunner(d, f, ex, store).run(identity("emu-resume"), select = { it.category == "calcoli_strumenti" })
        val before = store.load("emu-resume")!!.second.size
        for (other in listOf(identity("emu-resume", "MODEL"), identity("emu-resume").copy(commit = "other-build"), identity("emu-resume").copy(modelSha256 = "00"))) {
            val refused = try { BenchRunner(d, f, ex, store).run(other, select = { it.category == "calcoli_strumenti" }); false } catch (e: com.simone.jarvismobile.core.bench.ResumeIdentityMismatch) { true }
            assertTrue("a different run identity must refuse the resume", refused)
        }
        assertEquals(before, store.load("emu-resume")!!.second.size)
    }

    @Test fun theBundledDatasetShipsInsideTheApkAndValidatesOnAndroid() {
        val (d, f) = loaded
        assertEquals(emptyList<String>(), BenchDataset.validate(d, f))
        assertEquals(150, d.cases.size)
    }

    @Test fun theModelFreeControlRunsOnAndroidPersistsAndResumes() = runBlocking {
        val (d, f) = loaded
        val store = BenchStore(dir)
        val ex = PipelineControlExecutor(PipelineControlExecutor.chunkRetrieval(f), "faithful")
        val first = BenchRunner(d, f, ex, store).run(identity("emu1"), select = { it.category == "calcoli_strumenti" || it.category == "assenti" })
        assertEquals(22, first.size)
        assertTrue("every executed calculation is exact", first.filter { it.category == "calcoli_strumenti" }.all { it.passed == true })
        assertEquals("the stored file holds the same results", 22, store.load("emu1")!!.second.size)
        // resume: nothing is recomputed (an executor that would fail proves it)
        val boom = object : com.simone.jarvismobile.core.bench.BenchExecutor { override suspend fun execute(c: BenchCase, fixtures: com.simone.jarvismobile.core.bench.BenchFixtures) = error("must not run again") }
        val again = BenchRunner(d, f, boom, store).run(identity("emu1"), select = { it.category == "calcoli_strumenti" || it.category == "assenti" })
        assertEquals(first.map { it.id to it.passed }, again.map { it.id to it.passed })
        // the full suite: modelled-only cases are NOT executed, never faked
        val all = BenchRunner(d, f, ex).run(identity("emu2"))
        val s = BenchReport.summarize(all, d.cases.size)
        assertEquals(150, s.total); assertEquals(27, s.notExecuted); assertEquals(123, s.executed)
        val md = BenchReport.markdown(identity("emu2"), all, 150)
        assertTrue("NOT_MEASURED" in md && "NO — not a real-device run" in md && "emulator" in md)
    }

    @Test fun aSupportedAndAHallucinatedAnswerThroughTheRealCoordinatorArePublishedDifferently() = runBlocking {
        val (d, f) = loaded
        val coordinator = EntryPointAccessors.fromApplication(ctx, AssistantTaskWorker.WorkerEntryPoint::class.java).coordinator()
        OfflineGate.initialise(OfflineMode.STRICT_OFFLINE) // the run itself needs no network
        try {
            val ids = listOf("geo-01", "geo-07", "med-02", "abs-01", "abs-02")
            val retrieve = PipelineControlExecutor.chunkRetrieval(f)
            fun run(variant: String) = runBlocking {
                BenchRunner(d, f, CoordinatorBenchExecutor(coordinator, retrieve, scriptedAnswer = { c -> if (variant == "hallucinated") c.control?.hallucinated else c.control?.faithful }))
                    .run(identity("coord-$variant"), select = { it.id in ids })
            }
            val ok = run("faithful"); val bad = run("hallucinated")
            assertEquals(5, ok.count { it.executed }); assertEquals(5, bad.count { it.executed })
            // faithful: the supported answers pass and cite the right passage
            assertTrue(ok.filter { it.id in listOf("geo-01", "geo-07") }.all { it.dims[Dim.CITATION_VALID] == true && it.dims[Dim.FACT] == true })
            // hallucinated numbers are never published as verified
            val everest = bad.first { it.id == "geo-07" }
            assertFalse("a swapped number must not be verified", everest.falseProof)
            // an absent case is answered with a qualification, never a cited claim
            assertTrue(bad.filter { it.category == "assenti" }.none { it.falseProof })
        } finally { coordinator.testSeam = null; coordinator.newConversation() }
    }

    @Test fun theDeviceProbeReportsRealValuesOrNullNeverInventedZeros() {
        val s = BenchDeviceProbe.read(ctx)
        s.batteryPct?.let { assertTrue(it in 0..100) }
        s.batteryTemperatureC?.let { assertTrue(it in -20.0..100.0) }
        s.pssMb?.let { assertTrue(it > 0) }
        assertNotNull(BenchDeviceProbe.deviceKey(ctx))
        val perf = BenchDeviceProbe.perf(s, BenchDeviceProbe.read(ctx))
        assertNull("nothing the probe cannot see is filled in", perf.loadMs)
        assertNull(perf.firstTokenMsP50)
    }

    @Test fun exportHappensOnlyAfterTheUserConfirms() = runBlocking {
        val (d, f) = loaded
        val r = BenchRunner(d, f, PipelineControlExecutor(PipelineControlExecutor.chunkRetrieval(f))).run(identity("exp"), select = { it.category == "calcoli_strumenti" })
        assertNull(BenchExport.exportForUser(false, identity("exp"), r, 150))
        val b = BenchExport.exportForUser(true, identity("exp"), r, 150)!!
        assertEquals(BenchExport.preview(identity("exp"), r, 150), b.markdown)
        assertTrue(b.csv.lines().first().startsWith("id,category") && "<svg" in b.svg && "jarvis-bench-report-v1" in b.json)
    }
}
