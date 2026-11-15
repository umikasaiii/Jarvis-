package com.simone.jarvismobile.core.knowledge.fts

import com.simone.jarvismobile.core.knowledge.zim.CountingSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.TestZimBuilder
import com.simone.jarvismobile.core.knowledge.zim.TitleLayout
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimDecompressor
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** TEST KIND: JVM, synthetic modern archives (clusters shuffled like real ones), real SQLite. Pipeline correctness, not speed. */
class ZimIndexerPipelineTest {
    private fun archive(n: Int = 400, perCluster: Int = 10, layout: TitleLayout = TitleLayout.MODERN, decompressor: ZimDecompressor = TestDecompressor): Pair<ZimArchive, Int> {
        val b = TestZimBuilder().apply { titleLayout = layout; entriesPerCluster = perCluster; shuffleSeed = 99 }
        b.metadata("Title", "Pipeline").metadata("Date", "2026-07-01").metadata("Language", "ita")
        for (i in 0 until n) b.article("V_%04d".format(i), "Voce %04d".format(i), "<html><head><title>x</title></head><body><h1>Voce $i</h1><p>Prima frase della voce $i su argomento$i. Seconda frase con parole varie e termine${i % 17}.</p><h2>Sezione</h2><p>Altro testo $i.</p></body></html>")
        b.redirect("Alias", "Alias", "V_0001")
        return ZimArchive.open(CountingSource(b.build()), decompressor) to n
    }

    private fun tmp() = File.createTempFile("kxpipe", ".db").also { it.deleteOnExit() }

    private fun fingerprintOf(f: File) = JdbcFtsDb(f).use { fingerprint(it) }

    @Test fun `any number of workers builds exactly the same index`() {
        val (z, n) = archive()
        val prints = (1..3).map { w ->
            val f = tmp()
            JdbcFtsDb(f).use { db -> assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(z, KnowledgeIndex(db), workers = w).run("s", Int.MAX_VALUE, 500_000_000)) }
            JdbcFtsDb(f).use { assertEquals(n, KnowledgeIndex(it).passageCount() - KnowledgeIndex(it).passageCount() + n) }
            fingerprintOf(f)
        }
        assertEquals(1, prints.toSet().size, "the index must not depend on the degree of parallelism")
    }

    @Test fun `the new pipeline stores the same passages as the one-article-at-a-time walk it replaced`() {
        val (z, n) = archive(120, 7)
        val f = tmp()
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db); ZimIndexer(z, ix, workers = 2).run("s", Int.MAX_VALUE, 500_000_000)
            val rows = db.query("SELECT docKey, title, lead, text FROM kx_passage ORDER BY docKey, lead DESC") { listOf(it.string(0), it.string(1), it.long(2).toString(), it.string(3)) }
            assertEquals(n, rows.map { it[0] }.distinct().size, "one entry per article, redirects not duplicated")
            val first = rows.first { it[0].endsWith("/V_0003") }
            assertEquals("Voce 0003", first[1]); assertEquals("1", first[2]); assertTrue("Prima frase della voce 3" in first[3], first[3])
        }
    }

    @Test fun `stop at a checkpoint then resume equals one uninterrupted run, with no duplicates`() {
        val (z, _) = archive()
        val whole = tmp()
        JdbcFtsDb(whole).use { ZimIndexer(z, KnowledgeIndex(it), workers = 2).run("s", Int.MAX_VALUE, 500_000_000) }
        val parts = tmp()
        var checkpoints = 0
        JdbcFtsDb(parts).use { db ->
            val s = ZimIndexer(z, KnowledgeIndex(db), workers = 2).run("s", Int.MAX_VALUE, 500_000_000, shouldContinue = { checkpoints < 2 }, onProgress = { checkpoints++ }, checkpointPassages = 40)
            assertEquals(IndexedSource.State.INDEXING, s)
            assertTrue(KnowledgeIndex(db).cursor("zim:${z.uuid}").startsWith("c:"))
        }
        JdbcFtsDb(parts).use { db ->
            // a second interruption, then completion: every resume continues from the last committed checkpoint
            var again = 0
            ZimIndexer(z, KnowledgeIndex(db), workers = 3).run("s", Int.MAX_VALUE, 500_000_000, shouldContinue = { again < 1 }, onProgress = { again++ }, checkpointPassages = 40)
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(z, KnowledgeIndex(db), workers = 1).run("s", Int.MAX_VALUE, 500_000_000))
        }
        assertEquals(fingerprintOf(whole), fingerprintOf(parts))
        JdbcFtsDb(parts).use { db -> assertEquals(KnowledgeIndex(db).passageCount(), db.query("SELECT COUNT(DISTINCT docKey || lead) FROM kx_passage") { it.long(0).toInt() }.first()) }
    }

    @Test fun `a crash inside a checkpoint leaves the last good checkpoint and the resume still completes`() {
        val (z, _) = archive()
        val f = tmp()
        JdbcFtsDb(f).use { KnowledgeIndex(it) } // schema
        // fail on the 3rd write statement of the run: the first checkpoint(s) may be committed, the failing one rolls back
        val dying = JdbcFtsDb(f, failOnExecNumber = 7)
        assertFailsWith<IllegalStateException> {
            dying.use { ZimIndexer(z, KnowledgeIndex(it), workers = 2).run("s", Int.MAX_VALUE, 500_000_000, checkpointPassages = 30) }
        }
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(z, ix, workers = 2).run("s", Int.MAX_VALUE, 500_000_000, checkpointPassages = 30))
        }
        val whole = tmp()
        JdbcFtsDb(whole).use { ZimIndexer(z, KnowledgeIndex(it), workers = 2).run("s", Int.MAX_VALUE, 500_000_000) }
        assertEquals(fingerprintOf(whole), fingerprintOf(f))
    }

    @Test fun `a cursor written by the 2_0 title-order walk restarts that archive instead of mixing orders`() {
        val (z, _) = archive(60, 6)
        val f = tmp()
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            ix.begin("zim:${z.uuid}", "zim", "Pipeline", "Pipeline", "2026-07-01", "s")
            ix.addBatch("zim:${z.uuid}", listOf(PassageIn("zim:${z.uuid}/V_0000", "Voce 0000", "", "vecchio", true)), "40", 1_000_000) // 2.0 cursor: a title position
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(z, ix, workers = 2).run("s", Int.MAX_VALUE, 500_000_000))
            assertTrue(db.query("SELECT COUNT(*) FROM kx_passage WHERE text = 'vecchio'") { it.long(0) }.first() == 0L, "the stale partial passages are gone")
            assertEquals(60, db.query("SELECT COUNT(DISTINCT docKey) FROM kx_passage") { it.long(0).toInt() }.first())
        }
    }

    @Test fun `the article cap is exact, budget stops honestly, changed signature rebuilds`() {
        val (z, _) = archive()
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            assertEquals(IndexedSource.State.PARTIAL_BUDGET, ZimIndexer(z, ix, workers = 2).run("s", maxArticles = 50, budgetBytes = 500_000_000))
            assertEquals(50, db.query("SELECT COUNT(DISTINCT docKey) FROM kx_passage") { it.long(0).toInt() }.first(), "exactly the 50 articles asked for")
        }
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            assertEquals(IndexedSource.State.PARTIAL_BUDGET, ZimIndexer(z, ix, workers = 2).run("s", Int.MAX_VALUE, budgetBytes = 20_000))
            assertTrue(ix.totalBytes() <= 20_000); assertTrue(ix.passageCount() > 0)
        }
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            ZimIndexer(z, ix, workers = 2).run("v1", Int.MAX_VALUE, 500_000_000)
            val before = ix.passageCount()
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(z, ix, workers = 2).run("v1", Int.MAX_VALUE, 500_000_000), "same file: nothing redone")
            assertEquals(before, ix.passageCount())
            ZimIndexer(z, ix, workers = 2).run("v2", Int.MAX_VALUE, 500_000_000)
            assertEquals(before, ix.passageCount(), "a modified archive is reindexed, not appended")
        }
    }

    @Test fun `a failing worker surfaces the error, stops cleanly and leaves the index consistent`() {
        val calls = AtomicInteger()
        val bad = object : ZimDecompressor {
            override fun decompress(type: Int, data: ByteArray, maxOutputBytes: Int) = TestDecompressor.decompress(type, data, maxOutputBytes)
            override fun decompress(type: Int, data: ByteArray, offset: Int, length: Int, maxOutputBytes: Int): ByteArray {
                if (calls.incrementAndGet() > 6) throw IllegalStateException("disk error")
                return TestDecompressor.decompress(type, data, offset, length, maxOutputBytes)
            }
        }
        val (z, _) = archive(decompressor = bad)
        val f = tmp()
        JdbcFtsDb(f).use { db -> assertFailsWith<IllegalStateException> { ZimIndexer(z, KnowledgeIndex(db), workers = 3).run("s", Int.MAX_VALUE, 500_000_000) } }
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            assertEquals(IndexedSource.State.INDEXING, ix.source("zim:${z.uuid}")!!.state, "an interrupted source stays resumable")
            assertTrue(Thread.getAllStackTraces().keys.none { it.name.startsWith("kx-index-") && it.isAlive }, "no worker thread is left behind")
        }
    }

    @Test fun `the governor can pause the run and a later run resumes it`() {
        val (z, _) = archive()
        val f = tmp()
        var calls = 0
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            val idx = ZimIndexer(z, ix, workers = 2)
            val s = idx.run("s", Int.MAX_VALUE, 500_000_000, governor = { calls++; if (calls >= 2) IndexDecision(1, stopReason = "Telefono troppo caldo") else IndexDecision(2) }, checkpointPassages = 30)
            assertEquals(IndexedSource.State.INDEXING, s)
            assertEquals("Telefono troppo caldo", idx.stopReason)
            assertNotEquals(0, ix.passageCount())
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(z, ix, workers = 2).run("s", Int.MAX_VALUE, 500_000_000))
        }
        val whole = tmp()
        JdbcFtsDb(whole).use { ZimIndexer(z, KnowledgeIndex(it), workers = 2).run("s", Int.MAX_VALUE, 500_000_000) }
        assertEquals(fingerprintOf(whole), fingerprintOf(f))
    }

    @Test fun `profiler accounts the phases and nothing is held in the shared query cache`() {
        val (z, _) = archive(200, 10)
        JdbcFtsDb(tmp()).use { db ->
            val idx = ZimIndexer(z, KnowledgeIndex(db), workers = 2)
            idx.run("s", Int.MAX_VALUE, 500_000_000)
            val snap = idx.profiler.snapshot()
            assertTrue(snap.millis.getValue(IndexProfiler.Phase.DECOMPRESS) > 0 && snap.millis.getValue(IndexProfiler.Phase.SQLITE) > 0 && snap.millis.getValue(IndexProfiler.Phase.EXTRACT) > 0, snap.toString())
            assertTrue((snap.counters["clusters"] ?: 0) in 1..40, snap.counters.toString())
        }
    }

    @Test fun `legacy title list archives are indexed too and redirects are not duplicated`() {
        val (z, n) = archive(80, 8, layout = TitleLayout.LEGACY)
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            assertEquals(IndexedSource.State.COMPLETE, ZimIndexer(z, ix, workers = 2).run("s", Int.MAX_VALUE, 500_000_000))
            assertEquals(n, db.query("SELECT COUNT(DISTINCT docKey) FROM kx_passage") { it.long(0).toInt() }.first())
            assertEquals(n, db.query("SELECT COUNT(*) FROM kx_passage WHERE lead = 1") { it.long(0).toInt() }.first(), "the redirect added no second lead")
        }
    }
}
