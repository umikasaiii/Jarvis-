package com.simone.jarvismobile.core.bench

import com.simone.jarvismobile.core.knowledge.Evidence
import com.simone.jarvismobile.core.knowledge.KnowledgeGatherer
import com.simone.jarvismobile.core.knowledge.fts.JdbcFtsDb
import com.simone.jarvismobile.core.knowledge.fts.KnowledgeIndex
import com.simone.jarvismobile.core.knowledge.zim.CountingSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.TestZimBuilder
import com.simone.jarvismobile.core.knowledge.zim.TitleLayout
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimIndexer
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource
import java.io.File

/**
 * JVM harness: the fixture articles become two REAL synthetic ZIM archives (one per fixture collection), their leads are indexed in a real SQLite FTS4 database,
 * and retrieval runs through the production [KnowledgeGatherer] (title search with typo tolerance, BM25 index candidates, section selection, deep pass, support judge).
 */
class BenchHarness(val fixtures: BenchFixtures) {
    val sources: List<ZimKnowledgeSource>
    val index: KnowledgeIndex
    val reads: CountingSource
    private val byPath = fixtures.articles.associateBy { it.id.replace('.', '_') }

    init {
        val dbFile = File.createTempFile("benchfx", ".db").also { it.deleteOnExit() }
        index = KnowledgeIndex(JdbcFtsDb(dbFile))
        val list = ArrayList<ZimKnowledgeSource>(); var firstReads: CountingSource? = null
        for ((n, coll) in fixtures.articles.map { it.collection to it.date }.distinct().withIndex()) {
            val b = TestZimBuilder().apply { titleLayout = TitleLayout.MODERN; entriesPerCluster = 10; compression = 5; uuidSeed = 11 + n }
            b.metadata("Title", coll.first).metadata("Date", coll.second).metadata("Language", "ita")
            for (a in fixtures.articles.filter { it.collection == coll.first && it.date == coll.second }) {
                val html = buildString {
                    append("<html><head><title>${a.title}</title></head><body><h1>${a.title}</h1>")
                    a.sections.forEachIndexed { i, s -> if (i == 0) append("<p>${s.text}</p>") else append("<h2>${s.name}</h2><p>${s.text}</p>") }
                    append("</body></html>")
                }
                b.article(a.id.replace('.', '_'), a.title, html)
            }
            val src = CountingSource(b.build()); if (firstReads == null) firstReads = src
            val zim = ZimArchive.open(src, TestDecompressor)
            ZimIndexer(zim, index).run("bench-$n", maxArticles = 10_000, budgetBytes = 100_000_000)
            list += ZimKnowledgeSource(zim)
        }
        sources = list; reads = firstReads!!
    }

    fun retrieve(): suspend (BenchCase) -> RetrievedEvidence = { c ->
        val g = KnowledgeGatherer().gatherDetailed(c.turns.last(), sources, index, emptyList(), 5)
        RetrievedEvidence(g.evidence) { sc ->
            val path = sc.chunk.sourceId.substringAfterLast('/')
            val art = byPath[path]
            (art?.id ?: path) + "#" + (if (sc.chunk.section == sc.chunk.sourceTitle || sc.chunk.section.isBlank()) "lead" else sc.chunk.section)
        }
    }
}
