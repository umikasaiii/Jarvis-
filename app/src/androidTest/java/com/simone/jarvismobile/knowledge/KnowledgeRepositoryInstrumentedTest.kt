package com.simone.jarvismobile.knowledge

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.knowledge.index.KnowledgeIndexManager
import com.simone.jarvismobile.knowledge.zim.AndroidZimDecompressor
import com.simone.jarvismobile.knowledge.zim.SafByteSource
import com.simone.jarvismobile.knowledge.zim.ZimKnowledge
import com.simone.jarvismobile.offlinehub.ZimLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M2 — the PRODUCTION [KnowledgeRepository.gatherWithReport] → [com.simone.jarvismobile.core.knowledge.KnowledgeGatherer] →
 * EvidenceAssembler path, built from the real classes (real [SettingsRepository], [ZimLibrary], [ZimKnowledge], [KnowledgeIndexManager]) with
 * Android's real SQLite FTS4 and the real SAF byte source + zstd decoder over a committed synthetic ZIM. Only two narrow seams are used, both
 * limited to fixtures/faults: the index database NAME (a unique test database, never the user's `knowledge_fts.db`) and the verified-archive
 * lookup (a SAF-verified archive needs a user-picked folder, which cannot exist in a test). No language model is loaded.
 * It does NOT prove behaviour on real archives, nor the Hilt singleton instances, nor the Honor 200.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeRepositoryInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "kx_repo_" + java.util.UUID.randomUUID().toString().take(8) + ".db"
    private lateinit var zimKnowledge: ZimKnowledge
    private lateinit var manager: KnowledgeIndexManager
    private lateinit var repo: KnowledgeRepository
    private lateinit var bytes: SafByteSource
    private lateinit var source: ZimKnowledgeSource

    @Before fun setUp() {
        val settings = SettingsRepository(ctx)
        zimKnowledge = ZimKnowledge(ctx, ZimLibrary(ctx, settings), settings)
        manager = KnowledgeIndexManager(ctx, settings, zimKnowledge).also { it.dbName = dbName }
        repo = KnowledgeRepository(ctx, settings, zimKnowledge, manager)
        val f = File(ctx.cacheDir, "m2_retrieval_zstd.zim")
        InstrumentationRegistry.getInstrumentation().context.assets.open("zim/m2_retrieval_zstd.zim").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        bytes = SafByteSource(ctx, Uri.fromFile(f))
        val zim = ZimArchive.open(bytes, AndroidZimDecompressor)
        // production selective indexing (article leads) into the TEST database
        ZimIndexer(zim, manager.index).run(signature = f.length().toString(), maxArticles = 1000, budgetBytes = 5_000_000)
        source = ZimKnowledgeSource(zim)
        zimKnowledge.sourcesOverride = { listOf(source) to 0 }
    }

    @After fun tearDown() {
        zimKnowledge.sourcesOverride = null
        runCatching { bytes.close() }
        ctx.deleteDatabase(dbName) // only the unique test database
    }

    private fun texts(e: Evidence) = (e as? Evidence.Grounded)?.passages.orEmpty().joinToString(" ") { it.chunk.text + " " + it.supportWindow }

    @Test fun supportingLeadIsRetrievedWithACompleteReport() = runBlocking {
        val g = repo.gatherWithReport("Qual è la capitale di Valdoria?", 5)
        val e = g.evidence as Evidence.Grounded
        assertTrue(e.supportEstablished)
        assertTrue(texts(e), "Merenza" in texts(e))
        assertFalse(g.report.partial); assertFalse(g.report.unreadable)
        assertEquals(1, g.report.sources)
    }

    @Test fun anInternalSectionBeyondTheChunkCapIsRetrievedAndCitedWithItsSection() = runBlocking {
        val e = repo.gatherWithReport("Qual è la vetta più alta della Catena Selmira?", 5).evidence as Evidence.Grounded
        val p = e.passages.first { "Aldara" in it.chunk.text }
        assertEquals("Dati principali", p.chunk.section)
        assertTrue(p.chunk.sourceId.endsWith("/Catena_Selmira"))
    }

    @Test fun anIndexFoundArticleIsDeepenedToItsSection() = runBlocking {
        val g = repo.gatherWithReport("Quale organo produce la teloxina?", 5)
        assertTrue(texts(g.evidence), "isole centrali" in texts(g.evidence))
        assertTrue(g.report.deepened)
        assertTrue("bounded second pass", g.report.articlesRead <= 5)
    }

    @Test fun anInsufficientPassageIsNeverPromotedAndTheSupportingOneIs() = runBlocking {
        val calvera = repo.gatherWithReport("In che anno fu costruito il Ponte di Calvera?", 5).evidence as Evidence.Grounded
        assertTrue(calvera.supportEstablished)
        val withYear = calvera.passages.filter { "1388" in it.supportWindow }
        assertTrue(withYear.isNotEmpty() && withYear.all { it.chunk.section == "Storia" })
        val milvana = repo.gatherWithReport("In che anno fu costruita la Torre di Milvana?", 5).evidence
        assertFalse("a lead that says the year is unknown is not proof of a year", milvana is Evidence.Grounded && milvana.supportEstablished)
    }

    @Test fun anUnavailableIndexIsReportedAndTheArchiveStillAnswers() = runBlocking {
        val broken = KnowledgeIndexManager(ctx, SettingsRepository(ctx), zimKnowledge).also { it.dbName = "no/such/dir.db" } // invalid name: opening fails
        val r = KnowledgeRepository(ctx, SettingsRepository(ctx), zimKnowledge, broken)
        val g = r.gatherWithReport("Qual è la capitale di Valdoria?", 5)
        assertTrue("Merenza" in texts(g.evidence))
        assertTrue(g.report.indexFailed); assertTrue(g.report.partial); assertFalse(g.report.unreadable)
        // nothing found AND the index cannot be read: a read ERROR, never "no information"
        try { r.gatherWithReport("Qual è la capitale di Nemoria?", 5); fail("an unreadable index with no result must not look like an empty result") } catch (e: java.io.IOException) { /* expected */ } catch (e: RuntimeException) { /* the open failure itself */ }
    }

    @Test fun aHealthyArchiveTogetherWithAnUnreadableOneIsAPartialSearch() = runBlocking {
        zimKnowledge.sourcesOverride = { listOf(source) to 1 }
        val g = repo.gatherWithReport("Qual è la capitale di Valdoria?", 5)
        assertTrue("Merenza" in texts(g.evidence))
        assertTrue(g.report.partial); assertEquals(1, g.report.sourcesFailed); assertEquals(2, g.report.sources)
        assertFalse(g.report.unreadable)
        val none = repo.gatherWithReport("Qual è la capitale di Nemoria?", 5)
        assertTrue("no result while an archive was unread must be reported as a partial search", none.report.partial)
    }

    @Test fun eachRequestCarriesItsOwnReportEvenWhenTheyOverlap() = runBlocking {
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        zimKnowledge.sourcesOverride = { if (first.getAndSet(false)) { delay(400); listOf(source) to 1 } else listOf(source) to 0 }
        val slow = async(Dispatchers.Default) { repo.gatherWithReport("Qual è la capitale di Valdoria?", 5) }
        delay(100)
        val fast = async(Dispatchers.Default) { repo.gatherWithReport("Qual è la capitale di Valdoria?", 5) }
        val (a, b) = listOf(slow, fast).awaitAll()
        assertEquals("the slow request keeps ITS failed source although another finished first", 1, a.report.sourcesFailed)
        assertEquals("the fast request is not contaminated by the other's failure", 0, b.report.sourcesFailed)
    }

    @Test fun aCancelledRequestLeavesNothingBehindForTheNextOne() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        zimKnowledge.sourcesOverride = { entered.complete(Unit); delay(60_000); listOf(source) to 1 }
        var delivered: Any? = null
        val job = launch(Dispatchers.Default) { delivered = repo.gatherWithReport("Qual è la capitale di Valdoria?", 5) }
        withTimeout(30_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals("no late result from a cancelled search", null, delivered)
        zimKnowledge.sourcesOverride = { listOf(source) to 0 }
        val next = repo.gatherWithReport("Qual è la capitale di Valdoria?", 5)
        assertEquals("the next request does not inherit the cancelled one's state", 0, next.report.sourcesFailed)
        assertNotNull(next.evidence)
    }
}
