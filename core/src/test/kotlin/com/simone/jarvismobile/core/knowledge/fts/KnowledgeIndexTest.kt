package com.simone.jarvismobile.core.knowledge.fts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TEST KIND: JVM on real SQLite (FTS4 + fts4aux), synthetic passages. */
class KnowledgeIndexTest {
    private fun tmp(): File = File.createTempFile("kxtest", ".db").also { it.deleteOnExit() }
    private fun p(doc: String, title: String, text: String, lead: Boolean = true) = PassageIn(doc, title, "", text, lead)

    @Test fun `finds passages by content words that the title does not contain`() {
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            ix.begin("lib", "doc", "Libreria", "", "", "v1")
            ix.addBatch("lib", listOf(
                p("zim:1/Pancreas", "Pancreas", "Il pancreas è una ghiandola che produce l'insulina e gli enzimi digestivi."),
                p("zim:1/Fegato", "Fegato", "Il fegato è un organo che produce la bile e metabolizza i farmaci."),
                p("zim:1/Cuore", "Cuore", "Il cuore è l'organo che pompa il sangue nei vasi."),
            ), "3", 1_000_000)
            ix.finish("lib")
            val hits = ix.search("quale organo produce l'insulina?")
            assertEquals("Pancreas", hits.first().sourceTitle, hits.map { it.sourceTitle }.toString())
            assertTrue(hits.first().lead)
            assertEquals("lib", ix.sources().single().key)
        }
    }

    @Test fun `incremental - unchanged skipped, changed replaced, removed deleted`() {
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            assertTrue(ix.begin("f1", "doc", "F1", "", "", "100|1"))
            ix.addBatch("f1", listOf(p("f1", "Moto", "La catena della moto va lubrificata ogni mille chilometri.")), "1", 1_000_000); ix.finish("f1")
            assertFalse(ix.begin("f1", "doc", "F1", "", "", "100|1"), "same signature + COMPLETE = nothing to do")
            assertTrue(ix.begin("f1", "doc", "F1", "", "", "120|2"), "changed signature = reindex")
            assertEquals(0, ix.passageCount(), "the old passages are gone before the new ones are added")
            ix.addBatch("f1", listOf(p("f1", "Moto", "Il filtro dell'olio si cambia con l'olio.")), "1", 1_000_000); ix.finish("f1")
            assertTrue(ix.search("catena lubrificata").isEmpty())
            assertEquals(1, ix.search("filtro olio").size)
            ix.begin("f2", "doc", "F2", "", "", "1"); ix.addBatch("f2", listOf(p("f2", "Altro", "Contenuto del secondo file."), p("f2b", "Altro2", "Secondo contenuto distinto.")), "2", 1_000_000); ix.finish("f2")
            ix.removeDocument("f2", "f2b")
            assertEquals(1, ix.sources().first { it.key == "f2" }.passages)
            ix.removeSource("f1")
            assertEquals(listOf("f2"), ix.sources().map { it.key })
            assertTrue(ix.search("filtro olio").isEmpty(), "removed content is no longer found")
        }
    }

    @Test fun `the space budget is a hard limit and what fits stays searchable`() {
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            ix.begin("big", "doc", "Big", "", "", "1")
            val many = (1..400).map { p("d$it", "Voce $it", "Questa è la voce numero $it con parole di riempimento sufficienti per occupare spazio nell'indice.") }
            val stored = ix.addBatch("big", many, "400", budgetBytes = 20_000)
            assertTrue(stored in 1 until 400, "stored=$stored")
            assertTrue(ix.totalBytes() <= 20_000, "bytes=${ix.totalBytes()}")
            assertEquals(IndexedSource.State.PARTIAL_BUDGET, ix.source("big")!!.state)
            assertTrue(ix.search("voce numero 1").isNotEmpty())
        }
    }

    @Test fun `an interruption resumes from the last committed batch without duplicates`() {
        val f = tmp()
        val docs = (1..30).map { p("d$it", "Voce $it", "Contenuto unico della voce $it: argomento$it.") }
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            ix.begin("s", "doc", "S", "", "", "1")
            ix.addBatch("s", docs.take(10), "10", 1_000_000)
            ix.addBatch("s", docs.subList(10, 20), "20", 1_000_000)
        } // process "dies" here: state INDEXING, cursor 20
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            assertTrue(ix.begin("s", "doc", "S", "", "", "1"), "an interrupted source resumes")
            assertEquals("20", ix.cursor("s"))
            ix.addBatch("s", docs.drop(20), "30", 1_000_000); ix.finish("s")
            assertEquals(30, ix.passageCount())
            assertEquals(IndexedSource.State.COMPLETE, ix.source("s")!!.state)
            assertEquals(1, ix.search("argomento25").size)
        }
    }

    @Test fun `a crash inside a batch rolls the whole batch back`() {
        val f = tmp()
        val docs = (1..10).map { p("d$it", "Voce $it", "Testo della voce $it.") }
        JdbcFtsDb(f).use { db -> KnowledgeIndex(db).also { it.begin("s", "doc", "S", "", "", "1"); it.addBatch("s", docs.take(5), "5", 1_000_000) } }
        val dying = JdbcFtsDb(f, failOnExecNumber = 1)
        dying.use { db ->
            val ix = KnowledgeIndex(db) // schema exists: no exec happens while opening
            assertFailsWith<IllegalStateException> { ix.addBatch("s", docs.drop(5), "10", 1_000_000) }
        }
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            assertEquals(5, ix.passageCount(), "the half-written batch left nothing behind")
            assertEquals("5", ix.cursor("s"))
        }
    }

    @Test fun `typos are repaired against the index's own vocabulary`() {
        JdbcFtsDb(tmp()).use { db ->
            val ix = KnowledgeIndex(db)
            ix.begin("m", "doc", "Medicina", "", "", "1")
            ix.addBatch("m", listOf(p("ipt", "Ipertensione", "L'ipertensione arteriosa è l'aumento persistente della pressione del sangue nelle arterie.")), "1", 1_000_000); ix.finish("m")
            assertEquals("Ipertensione", ix.search("che cos'è l'ipertenzione arteriosa").first().sourceTitle)
            assertTrue(ix.search("cosa mangia il pinguino").isEmpty())
        }
    }

    @Test fun `a schema change rebuilds the cache and never fails`() {
        val f = tmp()
        JdbcFtsDb(f).use { db -> KnowledgeIndex(db).also { it.begin("s", "doc", "S", "", "", "1"); it.addBatch("s", listOf(p("d", "T", "testo")), "1", 1_000_000) } }
        JdbcFtsDb(f).use { db -> db.exec("UPDATE kx_meta SET value='0' WHERE key='schema'") }
        JdbcFtsDb(f).use { db -> val ix = KnowledgeIndex(db); assertEquals(0, ix.passageCount()); assertTrue(ix.sources().isEmpty()) }
    }

    @Test fun `scale - 30000 passages stay small and fast (JVM figures, not device figures)`() {
        val f = tmp()
        JdbcFtsDb(f).use { db ->
            val ix = KnowledgeIndex(db)
            ix.begin("big", "zim", "Big", "", "", "1")
            val words = listOf("fiume", "montagna", "città", "storia", "scienza", "poeta", "battaglia", "isola", "regno", "teoria", "chimica", "pianta")
            val t0 = System.nanoTime()
            (0 until 30_000).chunked(500).forEach { chunk ->
                ix.addBatch("big", chunk.map { i -> p("zim:x/A$i", "Voce $i", "La voce $i parla di ${words[i % 12]} e di ${words[(i / 12) % 12]} nel contesto numero $i con termine${i % 997}.") }, chunk.last().toString(), 500_000_000)
            }
            ix.finish("big")
            val build = (System.nanoTime() - t0) / 1e6
            val q0 = System.nanoTime()
            val hits = (1..20).map { ix.search("quale poeta parla del regno nel contesto numero 12345") }.last()
            val q = (System.nanoTime() - q0) / 1e6 / 20
            val mb = f.length() / 1_048_576.0
            println("FTS SCALE: 30000 passages built in %.0f ms; file %.1f MB (accounted %.1f MB); mean query %.1f ms; hits=%d".format(build, mb, ix.totalBytes() / 1_048_576.0, q, hits.size))
            assertTrue(hits.isNotEmpty())
            assertTrue(q < 400, "query too slow: $q ms")
        }
    }
}
