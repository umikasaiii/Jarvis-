package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.fts.JdbcFtsDb
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.zim.CountingSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 — retrieval over an AUTHENTIC archive: the openzim test file "Climate change by Wikipedia" (English, CC BY-SA, 9 MB, 20,568 entries, 2024-06).
 * Not committed: set JARVIS_ZIM_TESTDATA to the folder that holds it (as for the other real-file tests). It is the "mini" edition: articles are short
 * leads (no inner sections), so it proves reading, namespace, decompression, citation identity, index-found retrieval and absence on real data — NOT
 * the retrieval of deep sections of long articles, and not Italian Wikipedia.
 */
class M2RealArchiveTest {
    private class RQ(val q: String, val fact: String?, val byTitle: Boolean)
    private val qs = listOf(
        RQ("In what year was the Paris Agreement adopted?", "2015", true),
        RQ("What temperature increase limit does the Paris Agreement prefer?", "1.5", true),
        RQ("How much did global sea level rise between 1901 and 2018?", "15–25", true),
        RQ("What would Earth's average surface temperature be without the greenhouse effect?", "−18", true),
        RQ("Who proposed the greenhouse effect in 1824?", "Fourier", true),
        RQ("What is ocean acidification?", "decrease in the pH", true),
        RQ("Which marine organisms are especially vulnerable to ocean acidification?", "mollusks and corals", true),
        // the article is not named: it can only be found through the index
        RQ("Which international treaty adopted in 2015 covers climate change mitigation, adaptation and finance?", "Paris", false),
        RQ("Which gas insulates a planet from losing heat to space and raises its surface temperature?", "greenhouse", false),
        // absent from the archive
        RQ("What is the population of Atlantis?", null, true),
        RQ("Who won the 1998 football world cup?", null, true),
        RQ("What is the capital of Nemoria?", null, true),
    )

    @Test fun realArchive() {
        val f = System.getenv("JARVIS_ZIM_TESTDATA")?.let { File(it, "data_nons_wikipedia_en_climate_change_mini_2024-06.zim") }?.takeIf { it.isFile }
        assumeTrue(f != null, "real archive missing (set JARVIS_ZIM_TESTDATA)")
        val counting = CountingSource(f!!.readBytes())
        val zim = ZimArchive.open(counting, TestDecompressor)
        assertEquals('C', zim.articleNamespace)
        assertEquals("Climate change by Wikipedia", zim.metadata("Title"))
        val src = ZimKnowledgeSource(zim)
        val dbFile = File.createTempFile("m2real", ".db").also { it.deleteOnExit() }
        val ix = KnowledgeIndex(JdbcFtsDb(dbFile))
        val t0 = System.nanoTime()
        val state = ZimIndexer(zim, ix).run("real", maxArticles = 25_000, budgetBytes = 200_000_000)
        val indexMs = (System.nanoTime() - t0) / 1_000_000
        val out = StringBuilder("indexing: state=$state passages=${ix.passageCount()} dbBytes=${dbFile.length()} time=${indexMs}ms\n")
        var supported = 0; var answerable = 0; var falseSupport = 0; var absentSupported = 0; var absent = 0
        for ((label, opts) in listOf("baseline" to RetrievalOptions.LEGACY, "m2" to RetrievalOptions())) {
            supported = 0; answerable = 0; falseSupport = 0; absentSupported = 0; absent = 0
            val lat = ArrayList<Double>()
            val reads0 = counting.reads.get()
            src.clearCache()
            for (q in qs) {
                val t = System.nanoTime()
                val g = KnowledgeGatherer(options = opts).gatherDetailed(q.q, listOf(src), ix, emptyList(), 5)
                lat += (System.nanoTime() - t) / 1e6
                val e = g.evidence as? Evidence.Grounded
                val text = e?.passages.orEmpty().joinToString(" ") { it.chunk.text + " " + it.supportWindow }
                val ok = e?.supportEstablished == true
                if (q.fact == null) { absent++; if (ok) { absentSupported++; out.appendLine("  ABSENT SUPPORTED [$label] ${q.q}") } }
                else {
                    answerable++
                    if (ok && KnowledgeText.fold(text).contains(KnowledgeText.fold(q.fact))) supported++
                    else out.appendLine("  MISS [$label] (byTitle=${q.byTitle}, support=$ok) ${q.q}")
                    if (ok && !KnowledgeText.fold(text).contains(KnowledgeText.fold(q.fact))) falseSupport++
                }
            }
            lat.sort()
            out.appendLine("$label: supportedRecall=$supported/$answerable falseSupportAnswerable=$falseSupport absentSupported=$absentSupported/$absent p50=%.1fms p95=%.1fms zimReads=%d".format(lat[lat.size / 2], lat[((lat.size - 1) * 0.95).toInt()], counting.reads.get() - reads0))
            if (label == "m2") { assertEquals(0, absentSupported, "absent questions must not be supported"); assertEquals(0, falseSupport) }
        }
        // citation identity on real data: a passage's reference names the real article and archive
        val g = KnowledgeGatherer().gather("In what year was the Paris Agreement adopted?", listOf(src), ix, emptyList(), 5) as Evidence.Grounded
        val p = g.passages.first { "2015" in it.chunk.text }
        assertEquals("Paris Agreement", p.chunk.sourceTitle)
        assertTrue(p.chunk.sourceId.startsWith("zim:") && p.chunk.sourceId.endsWith("/Paris_Agreement"), p.chunk.sourceId)
        assertTrue("Climate change by Wikipedia" in p.chunk.reference && "2024-06-18" in p.chunk.reference, p.chunk.reference)
        File("/tmp/m2_real.txt").writeText(out.toString())
    }
}
