package com.simone.jarvismobile.bench

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.background.AssistantTaskWorker
import com.simone.jarvismobile.core.bench.BenchDataset
import com.simone.jarvismobile.core.bench.BenchReport
import com.simone.jarvismobile.core.bench.BenchRunner
import com.simone.jarvismobile.core.bench.BenchStore
import com.simone.jarvismobile.core.bench.PipelineControlExecutor
import com.simone.jarvismobile.core.bench.RunIdentity
import com.simone.jarvismobile.core.llm.SlotId
import com.simone.jarvismobile.core.net.OfflineMode
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.ModelProfileStore
import com.simone.jarvismobile.net.OfflineEntryPoint
import com.simone.jarvismobile.net.OfflineGate
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M4.4 — the REAL-DEVICE run, started by hand from a computer with adb on a phone that already has a real `.litertlm` model imported and selected:
 *
 *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.simone.jarvismobile.bench.M4DeviceRunInstrumentedTest \
 *       -Pandroid.testInstrumentationRunnerArguments.m4device=true [-Pandroid.testInstrumentationRunnerArguments.m4split=DEV]
 *
 * To resume an interrupted run: `-Pandroid.testInstrumentationRunnerArguments.m4resume=<runId>` (the id is in the report file name / logcat).
 * It is NOT in the CI class list and does nothing (skipped) unless `m4device=true` AND a model path is configured: CI/emulator can never produce a
 * "device" result with it. It loads the model through the guard-respecting `ModelLoader.autoLoad` (no "load anyway", no forced retry after a killed load),
 * keeps the persisted offline mode and puts it back, runs with STRICT_OFFLINE, computes the model's SHA-256 once, and writes the report files to the app's
 * external files folder (`.../files/m4/`) for `adb pull`. It never touches memory, notes, documents or archives.
 */
@RunWith(AndroidJUnit4::class)
class M4DeviceRunInstrumentedTest {
    @Test fun realModelRun() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("set -e m4device true to run on a real device", args.getString("m4device") == "true")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val entry = EntryPointAccessors.fromApplication(ctx, OfflineEntryPoint::class.java)
        val settings = entry.settings()
        val path = settings.modelPath.first(); val name = settings.modelName.first()
        assumeTrue("no model is selected in the app", path.isNotBlank() && File(path).isFile)
        val persisted = settings.offlineMode.first()
        OfflineGate.initialise(OfflineMode.STRICT_OFFLINE)
        try {
            val loadedOk = entry.modelLoader().autoLoad(SlotId.FAST, path, name)
            assumeTrue("the model did not load (see the Models screen / report): no benchmark is run on an unloaded model", loadedOk && entry.llmRouter().fast.loadState.value == LlmLoadState.LOADED)
            val profiles = entry.profiles()
            val sha = profiles.computeSha256(path)
            val profile = profiles.all().firstOrNull { it.sha256 == sha }
            val (data, fx) = BenchDataset.loadBundled()
            val start = BenchDeviceProbe.read(ctx)
            val coordinator = EntryPointAccessors.fromApplication(ctx, AssistantTaskWorker.WorkerEntryPoint::class.java).coordinator()
            val id = RunIdentity(
                // `-e m4resume <runId>` continues an interrupted run: same id, same store file. The runner refuses (ResumeIdentityMismatch) unless build, dataset,
                // model file + SHA-256, runtime, backend, slot, sampling and device are all identical, so two sessions are never mixed.
                runId = args.getString("m4resume")?.takeIf { it.isNotBlank() } ?: ("dev-" + System.currentTimeMillis()), kind = "MODEL", commit = com.simone.jarvismobile.BuildConfig.BUILD_ID, datasetFingerprint = BenchDataset.bundledFingerprint(),
                datasetVersion = data.version, fixturesVersion = fx.version, offlineMode = "STRICT_OFFLINE", modelFile = File(path).name, modelSha256 = sha,
                runtime = "litertlm", runtimeVersion = ModelProfileStore.LITERTLM_VERSION, backend = "CPU", slot = "FAST", configuredWindow = 4096,
                environment = "device:" + BenchDeviceProbe.deviceKey(ctx), startedAt = System.currentTimeMillis(),
            )
            val out = File(ctx.getExternalFilesDir(null), "m4").also { it.mkdirs() }
            val store = BenchStore(out)
            val split = args.getString("m4split")
            val scores = BenchRunner(data, fx, CoordinatorBenchExecutor(coordinator, PipelineControlExecutor.chunkRetrieval(fx)), store)
                .run(id, select = { split == null || it.split == split })
            val end = BenchDeviceProbe.read(ctx)
            val finalId = id.copy(performance = BenchDeviceProbe.perf(start, end, com.simone.jarvismobile.core.bench.PerfFacts(loadMs = profile?.lastLoadMs, pssBeforeMb = profile?.pssBeforeBytes?.div(1024 * 1024), pssAfterMb = profile?.pssAfterBytes?.div(1024 * 1024))))
            File(out, "report-${id.runId}.md").writeText(BenchReport.markdown(finalId, scores, data.cases.size))
            File(out, "report-${id.runId}.json").writeText(BenchReport.json(finalId, scores, data.cases.size))
            File(out, "cases-${id.runId}.csv").writeText(BenchReport.csv(scores))
            File(out, "chart-${id.runId}.svg").writeText(BenchReport.svgChart(finalId, scores))
            android.util.Log.i("M4Device", "report written to ${out.absolutePath} (run ${id.runId}); DEVICE_QUALIFIED is NOT granted by this run")
        } finally {
            OfflineGate.initialise(persisted)
        }
    }
}
