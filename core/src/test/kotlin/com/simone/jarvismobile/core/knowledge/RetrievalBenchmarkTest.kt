package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RetrievalBenchmarkTest {
    private fun fmt(r: RetrievalBench.Result): String = "%-34s R@3=%.2f R@5=%.2f P@3=%.2f MRR=%.2f sect@5=%.2f falseSupport(absent)=%d/%d falseSupport(answerable)=%d supportedR@5=%.2f relatedStrong=%d p50=%.1fms p95=%.1fms sqlite/q=%.1f zimReads/q=%.1f"
        .format(r.name, r.recall3, r.recall5, r.precisionAt3, r.mrr, r.sectionRecall5, r.falseSupportOnAbsent, r.absent, r.falseSupportAnswerable, r.supportedRecall5, r.relatedPromotions, r.p50ms, r.p95ms, r.sqlitePerQuery, r.zimReadsPerQuery)

    @Test fun baselineVersusM2() {
        val corpus = RetrievalBench.build()
        val f = File.createTempFile("bench", ".db").also { it.deleteOnExit() }
        val db = RetrievalBench.indexed(corpus, f)
        val lines = ArrayList<String>()
        val results = HashMap<String, RetrievalBench.Result>()
        for ((variant, opts) in listOf("baseline" to RetrievalOptions.LEGACY, "m2" to RetrievalOptions())) {
            for ((label, filter) in listOf<Pair<String, (RetrievalBench.Q) -> Boolean>>("ALL" to { true }, "DEV" to { !it.holdout }, "HOLDOUT" to { it.holdout }, "BLIND2" to { it.blind })) {
                corpus.source.clearCache()
                val ix = KnowledgeIndex(db)
                val g = KnowledgeGatherer(options = opts)
                val r = RetrievalBench.evaluate("$variant/$label", corpus, db, filter) { q -> g.gather(q, listOf(corpus.source), ix, emptyList(), 5) }
                results["$variant/$label"] = r
                lines += fmt(r)
                if (label == "ALL") { r.byCat.toSortedMap().forEach { (c, v) -> lines += "   $c ${v.first}/${v.second}" }; r.failures.forEach { lines += "   $it" } }
            }
        }
        File("/tmp/bench_compare.txt").writeText(lines.joinToString("\n"))
        lines.forEach { println(it) }
        assertTrue(corpus.questions.size >= 40)
        for (label in listOf("ALL", "DEV", "HOLDOUT", "BLIND2")) {
            val b = results["baseline/$label"]!!; val m = results["m2/$label"]!!
            assertTrue(m.recall5 >= b.recall5, "$label recall@5 regressed: ${m.recall5} < ${b.recall5}")
            assertTrue(m.sectionRecall5 >= b.sectionRecall5, "$label section recall regressed")
            assertTrue(m.falseSupportOnAbsent <= b.falseSupportOnAbsent, "$label false support on absent grew")
            assertTrue(m.falseSupportAnswerable <= b.falseSupportAnswerable, "$label false support on answerable questions grew")
            assertTrue(m.supportedRecall5 >= b.supportedRecall5, "$label supported recall regressed")
            assertTrue(m.relatedPromotions <= b.relatedPromotions, "$label RELATED promotions grew")
        }
    }
}
