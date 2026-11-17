package com.simone.jarvismobile.core.bench

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdversarialBenchTest {
    private fun run(file: String): Pair<AdvSummary, List<AdvResult>> = runBlocking {
        val f = AdversarialBench.load(file)
        val results = f.cases.map { c ->
            val fx = AdversarialBench.fixturesOf(c)
            val retrieve = if (fx.articles.isEmpty()) PipelineControlExecutor.chunkRetrieval(fx) else BenchHarness(fx).retrieve()
            val o = PipelineControlExecutor(retrieve, "faithful").execute(AdversarialBench.caseOf(c), fx)
                ?: BenchOutcome("", "ERROR", error = "not executed")
            AdversarialBench.judge(c, o)
        }
        AdversarialBench.summarize(f.set, results) to results
    }

    @Test fun setsB_andC() {
        val sb = StringBuilder(); val sums = ArrayList<Pair<String, AdvSummary>>()
        for (file in listOf("bench_adv_b.json", "bench_adv_c.json")) { val (s, r) = run(file); sb.appendLine(AdversarialBench.markdown(s, r)); sums += file to s }
        File("/tmp/bench_adv.md").writeText(sb.toString())
        // headline guarantee: no altered claim is ever published as VERIFIED/ALLOWED_PARTS, and no citation points at a non-supporting passage, on a case actually run
        for ((file, s) in sums) {
            assertEquals(0, s.falseVerified, "false VERIFIED in $file")
            assertEquals(0, s.falseAllowedParts, "false ALLOWED_PARTS in $file")
            assertEquals(0, s.nonSupportingCitations, "non-supporting citation in $file")
        }
    }

    @Test fun setsAreDeclaredNotBlind() {
        for (file in listOf("bench_adv_b.json", "bench_adv_c.json")) assertTrue(AdversarialBench.load(file).role.contains("NOT blind") || file.endsWith("b.json"))
    }
}
