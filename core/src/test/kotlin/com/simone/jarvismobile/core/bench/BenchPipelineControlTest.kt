package com.simone.jarvismobile.core.bench

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchPipelineControlTest {
    private companion object {
        // recorded on the first complete run of dataset 1.0.0 (JVM pipeline control, commit of M4); see docs/M4_BENCHMARK.md
        const val BASELINE_HALLUCINATED_FALSE_PROOFS = 23
        const val BASELINE_FAITHFUL_FALSE_PROOFS = 1
        const val BASELINE_FAITHFUL_PASSED = 105
    }

    private val loaded = BenchDataset.loadBundled()
    private val data = loaded.first
    private val fx = loaded.second

    private fun identity(kind: String, id: String) = RunIdentity(
        runId = id, kind = "PIPELINE_CONTROL", datasetFingerprint = "t", datasetVersion = data.version, fixturesVersion = fx.version, environment = "jvm", startedAt = 0,
    ).copy(commit = null)

    @Test fun controlRun() = runBlocking {
        val h = BenchHarness(fx)
        val out = StringBuilder()
        for (variant in listOf("faithful", "hallucinated")) {
            val scores = BenchRunner(data, fx, PipelineControlExecutor(h.retrieve(), variant)).run(identity(variant, "ctl-$variant"))
            val s = BenchReport.summarize(scores, data.cases.size)
            out.appendLine("== $variant executed=${s.executed} passed=${s.passed} failed=${s.failed} falseProofs=${s.falseProofs} notExecuted=${s.notExecuted}")
            s.categories.forEach { out.appendLine("  ${it.category}: ${it.passed}/${it.executed}") }
            scores.filter { it.executed && it.passed == false }.forEach { out.appendLine("  FAIL ${it.id} ${it.dims.filter { d -> d.value == false }.keys} ${it.notes}") }
            File("/tmp/bench_ctl_$variant.md").writeText(BenchReport.markdown(identity(variant, "ctl-$variant"), scores, data.cases.size))
        }
        val ex = PipelineControlExecutor(h.retrieve(), "faithful")
        for (id in listOf("doc-10", "doc-13", "abs-08", "mem-07", "med-09", "geo-11", "cyb-01", "sci-04", "ind-03")) {
            val c = data.cases.first { it.id == id }; val o = ex.execute(c, fx)!!
            out.appendLine("-- $id mode=${o.mode} support=${o.supportEstablished} retrieved=${o.retrievedPassages} cited=${o.citedPassages}\n   ${o.publishedText.replace("\n", " / ")}")
        }
        val hx = PipelineControlExecutor(h.retrieve(), "hallucinated")
        for (c in data.cases.filter { !it.requiresLlm && !it.requiresAndroidTools }) {
            val o = hx.execute(c, fx)!!; val sc = BenchScoring.score(c, o)
            if (sc.falseProof) out.appendLine("-- FALSEPROOF ${c.id} mode=${o.mode} cited=${o.citedPassages}\n   ${o.publishedText.replace("\n", " / ")}")
        }
        File("/tmp/bench_ctl.txt").writeText(out.toString())
        // ---- guards (a regression of the measured behaviour fails; an improvement is welcome and the baseline is then lowered on purpose) ----
        val executable = data.cases.filter { !it.requiresLlm && !it.requiresAndroidTools }
        val faithful = BenchRunner(data, fx, PipelineControlExecutor(h.retrieve(), "faithful")).run(identity("faithful", "g1"))
        val halluc = BenchRunner(data, fx, PipelineControlExecutor(h.retrieve(), "hallucinated")).run(identity("hallucinated", "g2"))
        assertEquals(123, faithful.count { it.executed }); assertEquals(27, faithful.count { !it.executed })
        // numeric/identifier substitutions are NEVER verified; single-word (non numeric) substitutions are the measured weakness (see docs/M4_BENCHMARK.md)
        val numericIds = executable.filter { "numeric" in it.tags }.map { it.id }.toSet()
        assertEquals(0, halluc.filter { it.id in numericIds }.count { it.falseProof }, "a swapped number was published as verified")
        assertTrue(halluc.count { it.falseProof } <= BASELINE_HALLUCINATED_FALSE_PROOFS, "false proofs rose above the recorded baseline: ${halluc.count { it.falseProof }}")
        assertTrue(faithful.count { it.falseProof } <= BASELINE_FAITHFUL_FALSE_PROOFS)
        assertTrue(faithful.count { it.passed == true } >= BASELINE_FAITHFUL_PASSED, "faithful pass count fell: ${faithful.count { it.passed == true }}")
        // an absent-information case never gets a cited/verified forbidden claim, even from a hallucinating candidate
        assertEquals(0, halluc.filter { it.category == "assenti" }.count { it.falseProof })
    }
}
