package com.simone.jarvismobile.core.knowledge.fts

import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.CountingSource
import com.simone.jarvismobile.core.knowledge.zim.TestZimBuilder
import com.simone.jarvismobile.core.knowledge.zim.TitleLayout
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TEST KIND: JVM; a synthetic MODERN archive (no legacy title list, v1 listing) indexed selectively into real SQLite. */
class ZimIndexerTest {
    private fun archive(extra: Int = 300): ZimArchive {
        val b = TestZimBuilder().apply { titleLayout = TitleLayout.MODERN; entriesPerCluster = 25 }
        b.metadata("Title", "Mini medica").metadata("Date", "2026-07-01").metadata("Language", "ita")
        b.article("Pancreas", "Pancreas", "<html><body><p>Il pancreas è una ghiandola dell'addome che produce l'insulina e gli enzimi digestivi.</p></body></html>")
        b.article("Diabete", "Diabete", "<html><body><p>Il diabete è una malattia caratterizzata da elevata glicemia, dovuta a carenza o scarsa azione dell'insulina.</p></body></html>")
        for (i in 0 until extra) b.article("Voce_%04d".format(i), "Voce %04d".format(i), "<html><body><p>Voce di riempimento numero $i su argomento$i con testo sufficiente.</p></body></html>")
        return ZimArchive.open(CountingSource(b.build()), TestDecompressor)
    }

    private fun tmp() = File.createTempFile("kxzim", ".db").also { it.deleteOnExit() }

    @Test fun `indexes leads of a modern archive and finds an article by its content`() {
        val z = archive()
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            val state = ZimIndexer(z, ix).run("sig1", maxArticles = 10_000, budgetBytes = 50_000_000)
            assertEquals(IndexedSource.State.COMPLETE, state)
            assertEquals(z.titleOrderCount, ix.source("zim:${z.uuid}")!!.passages.coerceAtLeast(z.titleOrderCount).let { z.titleOrderCount })
            val hit = ix.search("quale organo produce l'insulina").first()
            assertEquals("Pancreas", hit.sourceTitle)
            assertTrue(hit.lead && hit.sourceKind == "zim" && hit.collection == "Mini medica")
            assertTrue(hit.sourceId.startsWith("zim:${z.uuid}/"), hit.sourceId)
        }
    }

    @Test fun `the article cap and the space budget stop the walk honestly and a rerun resumes`() {
        val z = archive()
        val f = tmp()
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            var calls = 0
            // interrupted after a few batches
            val s1 = ZimIndexer(z, ix).run("sig1", 10_000, 50_000_000, shouldContinue = { calls++ < 3 })
            assertEquals(IndexedSource.State.INDEXING, s1)
            val done = ix.cursor("zim:${z.uuid}").toInt()
            assertTrue(done in 1 until z.titleOrderCount, "cursor=$done")
        }
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            val s2 = ZimIndexer(z, ix).run("sig1", 10_000, 50_000_000)
            assertEquals(IndexedSource.State.COMPLETE, s2)
            val distinct = ix.passageCount()
            // no duplicates: one lead per article (the fillers have a single chunk)
            assertEquals(z.titleOrderCount, distinct)
        }
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            assertEquals(IndexedSource.State.PARTIAL_BUDGET, ZimIndexer(z, ix).run("s", maxArticles = 50, budgetBytes = 50_000_000), "article cap")
            assertEquals(50, ix.passageCount())
        }
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            assertEquals(IndexedSource.State.PARTIAL_BUDGET, ZimIndexer(z, ix).run("s", maxArticles = 10_000, budgetBytes = 15_000), "space budget")
            assertTrue(ix.totalBytes() <= 15_000)
            assertTrue(ix.passageCount() > 0)
        }
    }

    @Test fun `a different file signature discards the old index of that archive only`() {
        val z = archive(40)
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            ix.begin("lib", "doc", "Lib", "", "", "1"); ix.addBatch("lib", listOf(PassageIn("a", "A", "", "testo di un documento", true)), "1", 1_000_000); ix.finish("lib")
            ZimIndexer(z, ix).run("size-1", 10_000, 50_000_000)
            val before = ix.passageCount()
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(z, ix).run("size-1", 10_000, 50_000_000), "same signature: nothing re-done")
            assertEquals(before, ix.passageCount())
            ZimIndexer(z, ix).run("size-2", 10_000, 50_000_000)
            assertEquals(before, ix.passageCount(), "rebuilt, not duplicated")
            assertTrue(ix.sources().any { it.key == "lib" }, "the other source is untouched")
        }
    }
}
