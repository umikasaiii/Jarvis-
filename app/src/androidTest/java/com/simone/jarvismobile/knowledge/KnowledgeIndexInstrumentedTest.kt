package com.simone.jarvismobile.knowledge

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simone.jarvismobile.core.knowledge.fts.IndexedSource
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.fts.PassageIn
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
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
 * KNOWLEDGE ENGINE 2.0 — the persistent index on the REAL Android SQLite (FTS4, unicode61, fts4aux) of the emulator, via the
 * production [AndroidFtsDb]; plus selective indexing of a tiny synthetic modern ZIM read through the real [SafByteSource].
 * It proves the SQL and the numeric-binding workaround work on Android's SQLite; it says nothing about speed or size on the
 * Honor 200 or about real archives.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeIndexInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AndroidFtsDb

    @Before fun setUp() { ctx.deleteDatabase("knowledge_fts.db"); db = AndroidFtsDb(ctx) }
    @After fun tearDown() { db.close() }

    @Test fun contentSearchTypoRepairIncrementalAndBudgetOnRealSqlite() {
        val ix = KnowledgeIndex(db)
        ix.clear()
        assertTrue(ix.begin("lib", "doc", "Medicina", "", "", "1"))
        ix.addBatch("lib", listOf(
            PassageIn("ipt", "Ipertensione", "", "L'ipertensione arteriosa è l'aumento persistente della pressione del sangue nelle arterie.", true),
            PassageIn("pan", "Pancreas", "", "Il pancreas è una ghiandola che produce l'insulina e gli enzimi digestivi.", true),
        ), "2", 10_000_000)
        ix.finish("lib")
        assertEquals("Pancreas", ix.search("quale organo produce l'insulina").first().sourceTitle)       // content word, not the title
        assertEquals("Ipertensione", ix.search("che cos'è l'ipertenzione arteriosa").first().sourceTitle)  // typo repaired from the vocabulary
        assertFalse(ix.begin("lib", "doc", "Medicina", "", "", "1"))                                       // unchanged: nothing to do
        assertTrue(ix.begin("lib", "doc", "Medicina", "", "", "2"))                                        // changed: replaced
        assertEquals(0, ix.passageCount())
        val stored = ix.addBatch("lib", (1..300).map { PassageIn("d$it", "Voce $it", "", "Testo della voce $it con parole di riempimento per occupare spazio nell'indice.", true) }, "300", 15_000)
        assertTrue(stored in 1 until 300)
        assertTrue(ix.totalBytes() <= 15_000)
        assertEquals(IndexedSource.State.PARTIAL_BUDGET, ix.source("lib")!!.state)
        ix.clear()
        assertEquals(0, ix.passageCount())
    }

    @Test fun selectiveZimIndexingThroughTheRealReader() {
        val f = File(ctx.cacheDir, "modern_listing_v1_zstd.zim")
        InstrumentationRegistry.getInstrumentation().context.assets.open("zim/modern_listing_v1_zstd.zim").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        SafByteSource(ctx, Uri.fromFile(f)).use { src ->
            val zim = ZimArchive.open(src, AndroidZimDecompressor)
            val ix = KnowledgeIndex(db); ix.clear()
            val state = ZimIndexer(zim, ix).run(signature = f.length().toString(), maxArticles = 1000, budgetBytes = 5_000_000)
            assertEquals(IndexedSource.State.COMPLETE, state)
            val hit = ix.search("qual è la capitale della Spagna").first()
            assertEquals("Madrid", hit.sourceTitle)
            assertTrue(hit.sourceId.startsWith("zim:"))
            ix.clear()
        }
    }
}
