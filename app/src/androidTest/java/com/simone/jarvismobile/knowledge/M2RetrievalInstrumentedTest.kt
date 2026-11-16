package com.simone.jarvismobile.knowledge

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.KnowledgeGatherer
import com.simone.jarvismobile.core.knowledge.RetrievalOptions
import com.simone.jarvismobile.core.knowledge.SupportJudge
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import com.simone.jarvismobile.knowledge.index.AndroidFtsDb
import com.simone.jarvismobile.knowledge.zim.AndroidZimDecompressor
import com.simone.jarvismobile.knowledge.zim.SafByteSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M2 — the new retrieval path on the REAL Android pieces: Android's SQLite (FTS4 `matchinfo` blob read through a Cursor, native byte order),
 * the production [AndroidFtsDb], [SafByteSource] + the real zstd decompressor over a synthetic ZIM, and the production [KnowledgeGatherer] with its
 * real EvidenceAssembler (no model). It does NOT build the Hilt graph's KnowledgeRepository (it needs DataStore + a verified library) and says
 * nothing about real archives or about speed on the Honor 200.
 */
@RunWith(AndroidJUnit4::class)
class M2RetrievalInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AndroidFtsDb
    private lateinit var file: File
    private val dbName = "kx_m2_" + java.util.UUID.randomUUID().toString().take(8) + ".db" // never the user's real index

    @Before fun setUp() {
        db = AndroidFtsDb(ctx, dbName)
        file = File(ctx.cacheDir, "modern_listing_v1_zstd.zim")
        InstrumentationRegistry.getInstrumentation().context.assets.open("zim/modern_listing_v1_zstd.zim").use { i -> file.outputStream().use { o -> i.copyTo(o) } }
    }
    @After fun tearDown() { db.close(); ctx.deleteDatabase(dbName) }

    private inline fun <T> withArchive(body: (ZimKnowledgeSource, KnowledgeIndex) -> T): T = SafByteSource(ctx, Uri.fromFile(file)).use { src ->
        val zim = ZimArchive.open(src, AndroidZimDecompressor)
        val ix = KnowledgeIndex(db); ix.clear()
        ZimIndexer(zim, ix).run(signature = file.length().toString(), maxArticles = 1000, budgetBytes = 5_000_000)
        body(ZimKnowledgeSource(zim), ix)
    }

    @Test fun matchinfoBlobIsReadAndScoredOnAndroidSqlite() = withArchive { _, ix ->
        val hits = ix.bm25Scan(SupportJudge.stems("capitale della Spagna").joinToString(" "), 2000)
        assertTrue("the BM25 scan returns rows on Android's SQLite", hits.isNotEmpty())
        assertTrue("scores are positive and finite (blob layout/endianness read correctly)", hits.all { it.second.isFinite() && it.second >= 0.0 } && hits.any { it.second > 0.0 })
        assertEquals("Madrid", ix.search("qual è la capitale della Spagna").first().sourceTitle)
    }

    @Test fun gatherThroughRealAssemblerAnswersAndReportsACompleteSearch() = withArchive { source, ix ->
        val r = KnowledgeGatherer().gatherDetailed("Qual è la capitale della Francia, Parigi?", listOf(source), ix, emptyList(), 5)
        val g = r.evidence as Evidence.Grounded
        assertTrue(g.supportEstablished)
        assertTrue(g.passages.any { "capitale della Francia" in it.chunk.text })
        assertFalse("a complete healthy search is not partial", r.report.partial)
        assertFalse(r.report.unreadable)
    }

    @Test fun legacyOptionsStillWorkOnTheSameIndex() = withArchive { source, ix ->
        val g = KnowledgeGatherer(options = RetrievalOptions.LEGACY).gather("qual è la capitale della Spagna", listOf(source), ix, emptyList(), 5)
        assertTrue(g is Evidence.Grounded)
    }

    @Test fun closedIndexIsAReportedPartialSearchNotAnEmptyResult() = withArchive { source, ix ->
        // an unusable index must surface as a failure of that stage; the archive alone still answers
        val dead = object : com.simone.jarvismobile.core.knowledge.fts.FtsDb {
            override fun exec(sql: String, vararg args: Any?) {}
            override fun <T> query(sql: String, vararg args: Any?, map: (com.simone.jarvismobile.core.knowledge.fts.FtsRow) -> T): List<T> = throw java.io.IOException("index unavailable")
            override fun <T> transaction(body: () -> T): T = body()
        }
        val r = KnowledgeGatherer().gatherDetailed("Qual è la capitale della Francia, Parigi?", listOf(source), KnowledgeIndex(dead), emptyList(), 5)
        assertTrue(r.report.indexFailed)
        assertTrue(r.report.partial)
        assertTrue((r.evidence as Evidence.Grounded).passages.any { "capitale della Francia" in it.chunk.text })
    }
}
