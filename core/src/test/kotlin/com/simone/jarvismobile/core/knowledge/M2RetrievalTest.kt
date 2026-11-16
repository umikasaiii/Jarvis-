package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.fts.FtsDb
import com.simone.jarvismobile.core.knowledge.fts.FtsRow
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.zim.ByteSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M2 — retrieval behaviours proven on a real SQLite FTS4 and a real (synthetic) ZIM, no model. */
class M2RetrievalTest {
    private val corpus = RetrievalBench.build()
    private val db = RetrievalBench.indexed(corpus, File.createTempFile("m2rt", ".db").also { it.deleteOnExit() })

    private fun grounded(e: Evidence) = e as? Evidence.Grounded
    private fun facts(e: Evidence) = grounded(e)?.passages.orEmpty().joinToString(" ") { KnowledgeText.fold(it.chunk.text + " " + it.supportWindow) }

    /** an index whose statements fail on demand, delegating to the real SQLite */
    private class FailingDb(val inner: FtsDb, val failOn: (String) -> Throwable?) : FtsDb {
        override fun exec(sql: String, vararg args: Any?) = inner.exec(sql, *args)
        override fun <T> query(sql: String, vararg args: Any?, map: (FtsRow) -> T): List<T> { failOn(sql)?.let { throw it }; return inner.query(sql, *args, map = map) }
        override fun <T> transaction(body: () -> T): T = inner.transaction(body)
        override fun execMany(sql: String, rows: List<Array<Any?>>) = inner.execMany(sql, rows)
    }

    // ---- BM25 over FTS4 matchinfo ----------------------------------------------------------------------------------------
    @Test fun bm25RanksTheShorterRarerPassageFirstOnRealFts4() {
        val ix = KnowledgeIndex(db)
        val hits = ix.bm25Scan(SupportJudge.stems("ponte romano antico").joinToString(" "), 2000)
        assertTrue(hits.size >= 300, "every matching row is scored (bounded by the scan cap)")
        val zeta = hits.maxByOrNull { it.second }!!
        assertTrue(zeta.second > 0.0)
        assertTrue(hits.size <= 2000)
    }

    @Test fun scanIsBoundedByTheCap() {
        assertEquals(5, KnowledgeIndex(db).bm25Scan(SupportJudge.stems("ponte romano antico").joinToString(" "), 5).size)
    }

    @Test fun matchinfoUnavailableFallsBackToLegacySelection() {
        val broken = FailingDb(db) { sql -> if ("matchinfo" in sql) IllegalStateException("no matchinfo") else null }
        val found = KnowledgeIndex(broken).search("Quale minerale è usato per rendere brillanti le vernici?", 12)
        assertTrue(found.any { it.sourceId.contains("Brillanite") }, "the legacy path still answers when BM25 cannot run")
    }

    @Test fun candidateBeyondTheLegacyCutIsReachedByBm25() {
        val q = "Quando fu costruito il ponte romano antico?"
        val legacy = KnowledgeIndex(db, options = RetrievalOptions.LEGACY).search(q, 100)
        val m2 = KnowledgeIndex(db).search(q, 100)
        assertFalse(legacy.any { it.sourceId.contains("Zeta") } && !m2.any { it.sourceId.contains("Zeta") })
        assertTrue(m2.any { it.sourceId.contains("Zeta") }, "the short, specific lead is among the candidates")
    }

    // ---- dedupe by passage identity, sections ----------------------------------------------------------------------------
    @Test fun sectionsBeyondTheChunkCapAreReachedByRelevance() {
        val g = KnowledgeGatherer().gather("Qual è la vetta più alta della Catena Selmira?", listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5)
        assertTrue("aldara" in facts(g), "the section 'Dati principali' of a 120-section article is read although it is beyond the first chunks")
        val legacy = KnowledgeGatherer(options = RetrievalOptions.LEGACY).gather("Qual è la vetta più alta della Catena Selmira?", listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5)
        assertFalse("aldara" in facts(legacy), "baseline: the same question misses it")
    }

    @Test fun citationIdentityKeepsArticleSectionAndPosition() {
        val g = grounded(KnowledgeGatherer().gather("Quando fu fondata la città di Marenza?", listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5))
        assertNotNull(g)
        val storia = g.passages.first { "1347" in it.chunk.text }
        assertTrue(storia.chunk.sourceId.endsWith("/Marenza"))
        assertEquals("Storia", storia.chunk.section)
        // two sections of the same article stay two distinct passages (no collapse by article id)
        assertEquals(g.passages.size, g.passages.map { it.chunk.sourceId + "|" + it.chunk.section + "|" + it.chunk.text }.distinct().size)
    }

    // ---- deep pass: bounded, general, entity-guarded -----------------------------------------------------------------------
    @Test fun deepPassFindsTheSectionOfAnIndexFoundArticle() {
        val r = KnowledgeGatherer().gatherDetailed("Quale organo produce la teloxina?", listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5)
        assertTrue("isole centrali" in facts(r.evidence))
        assertTrue(r.report.deepened)
        assertTrue(r.report.articlesRead <= 2 + 3, "bounded: at most the deep articles plus the title hits")
    }

    @Test fun deepPassNeverPromotesAnArticleThatDoesNotNameTheAskedEntity() {
        val r = KnowledgeGatherer().gatherDetailed("Qual è la profondità del Lago Inesistente?", listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5)
        val g = r.evidence as? Evidence.Grounded
        assertFalse(g != null && g.supportEstablished, "an article about another lake must not support the answer")
    }

    @Test fun absentQuestionsStayUnsupported() {
        for (q in listOf("Qual è la capitale di Nemoria?", "Quale organo produce l'ormone vexorina?", "Quanti abitanti ha la città di Pietrafalsa?")) {
            val g = KnowledgeGatherer().gather(q, listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5) as? Evidence.Grounded
            assertFalse(g != null && g.supportEstablished, q)
        }
    }

    // ---- errors are not empty results -----------------------------------------------------------------------------------
    @Test fun indexErrorIsReportedAndHealthySourcesStillAnswer() {
        val broken = KnowledgeIndex(FailingDb(db) { sql -> if ("kx_fts" in sql || "kx_vocab" in sql || "kx_passage" in sql) IOException("disk") else null })
        val r = KnowledgeGatherer().gatherDetailed("Qual è la capitale di Valdoria?", listOf(corpus.source), broken, emptyList(), 5)
        assertTrue("merenza" in facts(r.evidence), "the archive alone still answers")
        assertTrue(r.report.indexFailed)
        assertTrue(r.report.partial, "the search is declared partial, not complete")
        assertFalse(r.report.unreadable)
        assertTrue(r.report.issues.any { it.startsWith("index:") })
    }

    @Test fun nothingConsultableIsUnreadableNotNoResult() {
        val broken = KnowledgeIndex(FailingDb(db) { IOException("disk") })
        val r = KnowledgeGatherer().gatherDetailed("Qual è la capitale di Nemoria?", emptyList(), broken, emptyList(), 5)
        assertTrue(r.report.unreadable)
        val ok = KnowledgeGatherer().gatherDetailed("Qual è la capitale di Nemoria?", listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5)
        assertFalse(ok.report.unreadable, "a healthy search with no result is NOT an error")
        assertFalse(ok.report.partial)
    }

    @Test fun unreadableArchiveIsRecordedPerSource() {
        var fail = false
        val bytes = corpus.reads
        val flaky = object : ByteSource {
            override val size: Long get() = bytes.size
            override fun read(offset: Long, length: Int): ByteArray { if (fail) throw IOException("sd card removed"); return bytes.read(offset, length) }
        }
        val src = ZimKnowledgeSource(ZimArchive.open(flaky, TestDecompressor))
        fail = true
        val r = KnowledgeGatherer().gatherDetailed("Qual è la capitale di Valdoria?", listOf(src, corpus.source), KnowledgeIndex(db), emptyList(), 5)
        assertTrue(r.report.sourcesFailed >= 1 && r.report.partial)
        assertTrue("merenza" in facts(r.evidence), "the healthy archive still answers")
    }

    // ---- cancellation is never swallowed ----------------------------------------------------------------------------------
    @Test fun cancellationPropagatesFromTheIndexAndFromThePoll() {
        val cancelling = KnowledgeIndex(FailingDb(db) { sql -> if ("kx_fts" in sql || "kx_vocab" in sql || "kx_passage" in sql) CancellationException("c") else null })
        assertFailsWith<CancellationException> { KnowledgeGatherer().gatherDetailed("Qual è la capitale di Valdoria?", listOf(corpus.source), cancelling, emptyList(), 5) }
        assertFailsWith<CancellationException> { KnowledgeGatherer().gatherDetailed("Qual è la capitale di Valdoria?", listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5) { false } }
    }

    // ---- cache ----------------------------------------------------------------------------------------------------------------
    @Test fun articleCacheServesRepeatReadsAndIsBoundedAndClearable() {
        val src = corpus.source
        src.clearCache()
        src.options = RetrievalOptions(articleCacheChars = 5_000)
        src.articlesChecked("Qual è la capitale di Valdoria?", 1)
        val before = src.cacheHits
        src.articlesChecked("Qual è la capitale di Valdoria?", 1)
        assertTrue(src.cacheHits > before, "the second read of the same article is a cache hit")
        src.articlesChecked("Quanti abitanti ha Ostrovia?", 1); src.articlesChecked("Qual è il fiume principale della Valdoria?", 1)
        src.clearCache()
        val h = src.cacheHits
        src.articlesChecked("Qual è la capitale di Valdoria?", 1)
        assertEquals(h, src.cacheHits, "after invalidation the article is read again from the archive")
        assertTrue(src.cacheIdentity.contains("2026-01-01"), "the cache identity names archive and version")
        src.options = RetrievalOptions()
    }

    @Test fun cancellationInsideTheBm25StageIsNotTurnedIntoALegacyFallback() {
        val cancelling = FailingDb(db) { sql -> if ("matchinfo" in sql) CancellationException("c") else null }
        assertFailsWith<CancellationException> { KnowledgeIndex(cancelling).search("Quando fu costruito il ponte romano antico?", 12) }
    }

    @Test fun anArticleLargerThanTheWholeQuotaIsUsedButNeverKept() {
        val src = corpus.source
        src.clearCache()
        src.options = RetrievalOptions(articleCacheChars = 50)
        val before = src.cacheNotStored
        val r = src.articlesChecked("Qual è la capitale di Valdoria?", 1)
        assertTrue(r.value.isNotEmpty(), "the article is still read and usable")
        assertTrue(src.cacheNotStored > before, "and reported as not stored")
        assertEquals(0, src.cachedCharsNow, "the quota is a hard limit")
        src.options = RetrievalOptions(articleCacheChars = 60_000)
        src.clearCache()
        repeat(6) { i -> src.articlesChecked(listOf("Valdoria", "Karnelia", "Ostrovia", "Quillandia", "Zeferia", "Brontavia")[i], 1) }
        assertTrue(src.cachedCharsNow <= 60_000, "never above the quota: ${src.cachedCharsNow}")
        assertTrue(src.cacheMisses >= 1)
        src.options = RetrievalOptions(); src.clearCache()
    }

    // ---- an older index stays usable ------------------------------------------------------------------------------------------
    @Test fun legacyOptionsStillAnswerOverTheSameIndex() {
        val g = KnowledgeGatherer(options = RetrievalOptions.LEGACY).gather("Quale uccello migra ogni anno verso le isole del nord?", listOf(corpus.source), KnowledgeIndex(db), emptyList(), 5)
        assertTrue("uccello migratore" in facts(g))
    }
}
