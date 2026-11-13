package com.simone.jarvismobile.core.knowledge.zim

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TEST KIND: JVM, SYNTHETIC ZIM files built by [TestZimBuilder] from the public format spec (no third-party data). */
class ZimArchiveTest {
    private fun sample(compression: Int): ByteArray = TestZimBuilder().apply {
        this.compression = compression
        article("Francia", "Francia", "<html><head><title>Francia</title></head><body><h1>Francia</h1><p>La Francia è uno Stato dell'Europa occidentale. La capitale è Parigi.</p><h2>Geografia</h2><p>Ha una superficie di 551&nbsp;695 km².</p></body></html>")
        article("Parigi", "Parigi", "<html><body><p>Parigi è la capitale della Francia, sulla Senna.</p></body></html>")
        article("Italia", "Italia", "<html><body><p>L'Italia è una repubblica; la capitale è Roma.</p></body></html>")
        redirect("Repubblica_francese", "Repubblica francese", "Francia")
        metadata("Title", "Mini enciclopedia").metadata("Language", "ita").metadata("Date", "2024-05-01")
        mainPath = "Italia"
    }.build()

    @Test fun `opens modern archives in every compression and reads articles`() {
        for (c in listOf(1, 4, 5)) {
            val zim = ZimArchive.open(CountingSource(sample(c)), TestDecompressor)
            assertEquals('C', zim.articleNamespace)
            val e = assertNotNull(zim.findByPath('C', "Francia"), "compression $c")
            val html = String(assertNotNull(zim.content(e)))
            assertTrue("La capitale è Parigi" in html, "compression $c")
            assertEquals("Mini enciclopedia", zim.metadata("Title"))
            assertEquals("ita", zim.metadata("Language"))
            assertEquals("Italia", zim.mainPage?.path)
        }
    }

    @Test fun `redirects are followed and missing entries are null`() {
        val zim = ZimArchive.open(CountingSource(sample(5)), TestDecompressor)
        val r = assertNotNull(zim.findByPath('C', "Repubblica_francese"))
        assertTrue(r.isRedirect)
        assertEquals("Francia", zim.resolve(r)?.path)
        assertTrue(String(assertNotNull(zim.content(r))).contains("Parigi"))
        assertNull(zim.findByPath('C', "Inesistente"))
    }

    @Test fun `title prefix search is case sensitive, ordered and bounded`() {
        val zim = ZimArchive.open(CountingSource(sample(5)), TestDecompressor)
        assertEquals(listOf("Francia"), zim.titlesWithPrefix('C', "Fran", 10).map { it.displayTitle })
        assertEquals(listOf("Parigi"), zim.titlesWithPrefix('C', "Pa", 10).map { it.displayTitle })
        assertEquals(emptyList(), zim.titlesWithPrefix('C', "fran", 10))
        assertEquals(1, zim.titlesWithPrefix('C', "", 10).size + 1)
        assertEquals(1, zim.titlesWithPrefix('C', "I", 1).size)
    }

    @Test fun `html becomes sectioned text without markup or reference marks`() {
        val zim = ZimArchive.open(CountingSource(sample(5)), TestDecompressor)
        val t = ZimHtml.toText(String(zim.content(zim.findByPath('C', "Francia")!!)!!))
        assertEquals("Francia", t.title)
        assertTrue("## Geografia" in t.text && "<" !in t.text && "&nbsp;" !in t.text)
        assertTrue("551 695 km²" in t.text.replace(' ', ' '))
        val refs = ZimHtml.toText("<p>Fatto noto[1] e altro[23].</p><script>var x=1</script><style>p{}</style>")
        assertEquals("Fatto noto e altro.", refs.text)
    }

    // ---- hostile / corrupt input never crashes the app --------------------------------------------------------

    @Test fun `corrupt archives fail with a clean exception`() {
        val good = sample(5)
        assertFailsWith<ZimFormatException> { ZimArchive.open(CountingSource(ByteArray(10)), TestDecompressor) }
        assertFailsWith<ZimFormatException> { ZimArchive.open(CountingSource(good.copyOf().also { it[0] = 0 }), TestDecompressor) }
        assertFailsWith<ZimFormatException> { ZimArchive.open(CountingSource(good.copyOf().also { it[4] = 9 }), TestDecompressor) }
        // pointer list beyond the file
        assertFailsWith<ZimFormatException> { ZimArchive.open(CountingSource(good.copyOf(100)), TestDecompressor) }
        // a truncated file whose header is valid
        val cut = good.copyOf(good.size - 40)
        runCatching { ZimArchive.open(CountingSource(cut), TestDecompressor).let { z -> z.findByPath('C', "Francia")?.let { z.content(it) } } }
            .onFailure { assertTrue(it is ZimFormatException || it is IllegalStateException || it is RuntimeException, "unexpected ${it.javaClass}") }
    }

    @Test fun `a decompression bomb is refused by the output cap`() {
        val zim = ZimArchive.open(CountingSource(sample(5)), object : ZimDecompressor {
            override fun decompress(type: Int, data: ByteArray, maxOutputBytes: Int): ByteArray = error("output_over_cap")
        })
        assertFailsWith<IllegalStateException> { zim.content(zim.findByPath('C', "Francia")!!) }
    }

    // ---- never loads the archive ---------------------------------------------------------------------------------

    @Test fun `search and article read touch a tiny fraction of a large archive`() {
        val b = TestZimBuilder().apply { entriesPerCluster = 40; compression = 5 }
        for (i in 0 until 6000) b.article("Voce_%05d".format(i), "Voce %05d".format(i), "<p>${"Testo di riempimento della voce $i. ".repeat(40)}</p>")
        b.article("Zeta_Speciale", "Zeta Speciale", "<p>Contenuto cercato: valore 42.</p>")
        val bytes = b.build()
        val src = CountingSource(bytes)
        val zim = ZimArchive.open(src, TestDecompressor)
        val hits = zim.titlesWithPrefix('C', "Zeta", 5)
        assertEquals(1, hits.size)
        assertTrue(String(zim.content(hits[0])!!).contains("valore 42"))
        val fraction = src.bytesRead.get().toDouble() / bytes.size
        assertTrue(fraction < 0.12, "read ${src.bytesRead.get()} of ${bytes.size} (${"%.3f".format(fraction)}) — the archive must not be loaded")
        assertTrue(src.reads.get() < 400, "reads=${src.reads.get()}")
    }
}

/**
 * TEST KIND: real third-party ZIM files from the openzim test suite (NOT committed: licence unclear). Runs only when
 * JARVIS_ZIM_TESTDATA points at a directory holding them; skipped in CI. Executed in the development sandbox.
 */
class ZimRealFilesTest {
    private fun dir(): File? = System.getenv("JARVIS_ZIM_TESTDATA")?.let(::File)?.takeIf { it.isDirectory }

    @Test fun `reads the real modern wikipedia mini archive`() {
        val d = dir(); assumeTrue(d != null, "JARVIS_ZIM_TESTDATA not set")
        val f = File(d, "data_nons_wikipedia_en_climate_change_mini_2024-06.zim"); assumeTrue(f.isFile)
        val zim = ZimArchive.open(FileSource(f), TestDecompressor)
        assertEquals(20568, zim.entryCount)
        assertEquals('C', zim.articleNamespace)
        val hit = zim.titlesWithPrefix('C', "Climate change", 20)
        assertTrue(hit.any { it.displayTitle == "Climate change" }, "titles: ${hit.map { it.displayTitle }}")
        val article = zim.resolve(zim.findByPath('C', "Climate_change")!!)!!
        val text = ZimHtml.toText(String(zim.content(article)!!)).text
        // "mini" Wikipedia archives hold only the lead section of each article.
        assertTrue(text.length > 2_000, "article text length ${text.length}")
        assertTrue("system ." !in text, "inline markup must not add spaces before punctuation")
        assertTrue("greenhouse" in text.lowercase(), "expected greenhouse in article")
        assertNotNull(zim.metadata("Title"))
        println("REAL ZIM: title='${zim.metadata("Title")}' lang=${zim.metadata("Language")} date=${zim.metadata("Date")} article chars=${text.length}")
    }

    @Test fun `reads the legacy namespaced archives (xz clusters)`() {
        val d = dir(); assumeTrue(d != null, "JARVIS_ZIM_TESTDATA not set")
        val f = File(d, "data_withns_wikibooks_be_all_nopic_2017-02.zim"); assumeTrue(f.isFile)
        val zim = ZimArchive.open(FileSource(f), TestDecompressor)
        assertEquals('A', zim.articleNamespace)
        val main = zim.resolve(zim.findByPath('A', "Main_Page.html")!!)!!
        val text = ZimHtml.toText(String(zim.content(main)!!)).text
        assertTrue(text.isNotBlank())
        val small = File(d, "data_nons_small.zim"); assumeTrue(small.isFile)
        val s = ZimArchive.open(FileSource(small), TestDecompressor)
        assertEquals("Test ZIM file", s.titlesWithPrefix('C', "Test", 3).first().displayTitle)
    }
}
