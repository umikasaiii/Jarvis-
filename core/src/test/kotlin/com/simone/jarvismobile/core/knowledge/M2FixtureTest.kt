package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.fts.JdbcFtsDb
import com.simone.jarvismobile.core.knowledge.zim.CountingSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The committed Android fixture (`m2_retrieval_zstd.zim`) behaves, on the JVM, exactly as the instrumented repository test asserts. */
class M2FixtureTest {
    private fun fixture(): ByteArray = listOf(File("../app/src/androidTest/assets/zim/m2_retrieval_zstd.zim"), File("app/src/androidTest/assets/zim/m2_retrieval_zstd.zim")).first { it.isFile }.readBytes()

    private fun run(q: String): Gathered {
        val zim = ZimArchive.open(CountingSource(fixture()), TestDecompressor)
        val ix = KnowledgeIndex(JdbcFtsDb(File.createTempFile("m2fx", ".db").also { it.deleteOnExit() }))
        ZimIndexer(zim, ix).run("fx", maxArticles = 1000, budgetBytes = 5_000_000)
        return KnowledgeGatherer().gatherDetailed(q, listOf(ZimKnowledgeSource(zim)), ix, emptyList(), 5)
    }
    private fun txt(e: Evidence) = (e as? Evidence.Grounded)?.passages.orEmpty().joinToString(" ") { it.chunk.text + " " + it.supportWindow }

    @Test fun lead() { val g = run("Qual è la capitale di Valdoria?"); assertTrue((g.evidence as Evidence.Grounded).supportEstablished); assertTrue("Merenza" in txt(g.evidence)); assertFalse(g.report.partial) }
    @Test fun longArticleSection() {
        val p = (run("Qual è la vetta più alta della Catena Selmira?").evidence as Evidence.Grounded).passages.first { "Aldara" in it.chunk.text }
        assertEquals("Dati principali", p.chunk.section)
    }
    @Test fun deepSection() { val g = run("Quale organo produce la teloxina?"); assertTrue("isole centrali" in txt(g.evidence), txt(g.evidence)); assertTrue(g.report.deepened) }
    @Test fun insufficientNotPromoted() {
        val c = run("In che anno fu costruito il Ponte di Calvera?").evidence as Evidence.Grounded
        assertTrue(c.supportEstablished); assertTrue(c.passages.filter { "1388" in it.supportWindow }.all { it.chunk.section == "Storia" })
        val m = run("In che anno fu costruita la Torre di Milvana?").evidence
        assertFalse(m is Evidence.Grounded && m.supportEstablished)
    }
}
