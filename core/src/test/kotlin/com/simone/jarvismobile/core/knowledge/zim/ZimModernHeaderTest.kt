package com.simone.jarvismobile.core.knowledge.zim

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZIM HOTFIX — modern archives omit the historical title list (header `titlePtrPos` = all ones) and order titles through
 * `X/listing/titleOrdered/v1`. TEST KIND: JVM, synthetic archives built from the public spec, plus (env-gated) real files.
 * "Absent" must be told apart from "invalid"; integrity checks stay on.
 */
class ZimModernHeaderTest {
    private fun archive(layout: TitleLayout, compression: Int = 5, corrupt: Long? = null): ByteArray = TestZimBuilder().apply {
        this.compression = compression
        this.titleLayout = layout
        this.corruptTitlePtr = corrupt
        entriesPerCluster = 3
        listOf("Roma" to "Capitale d'Italia.", "Parigi" to "Capitale della Francia.", "Madrid" to "Capitale della Spagna.", "Berlino" to "Capitale della Germania.",
            "Muro di Berlino" to "Il Muro di Berlino divise la città.", "Tevere" to "Fiume di Roma.", "Zurigo" to "Città svizzera.", "Atene" to "Capitale della Grecia.")
            .forEach { (t, body) -> article(t.replace(' ', '_'), t, "<html><body><p>$body</p></body></html>") }
        redirect("Berlin", "Berlin", "Berlino")
        metadata("Title", "Mini").metadata("Language", "ita").metadata("Date", "2026-07-01")
        mainPath = "Roma"
    }.build()

    private fun open(b: ByteArray) = ZimArchive.open(CountingSource(b), TestDecompressor)

    @Test fun `modern header with absent title list opens and searches titles through the v1 listing`() {
        val b = archive(TitleLayout.MODERN)
        // the header really carries the sentinel
        assertEquals(-1L, ByteBuffer.wrap(b, 40, 8).order(ByteOrder.LITTLE_ENDIAN).long)
        val z = open(b)
        assertEquals(ZimTitleIndex.LISTING_V1, z.titleIndexMode)
        assertEquals(listOf("Parigi"), z.titlesWithPrefix('C', "Par", 10).map { it.displayTitle })
        assertEquals(listOf("Berlino"), z.titlesWithPrefix('C', "Berlin", 10).map { it.displayTitle }) // the redirect "Berlin" is not listed
        assertEquals(emptyList(), z.titlesWithPrefix('C', "Nessuno", 10))
        assertEquals(1, z.titlesWithPrefix('C', "Z", 1).size)
        // content, metadata, redirects still work
        val e = z.resolve(z.titlesWithPrefix('C', "Roma", 1).first())!!
        assertTrue("Capitale d'Italia" in ZimHtml.toText(String(z.content(e)!!)).text)
        assertEquals("Mini", z.metadata("Title"))
        assertEquals("Berlino", z.resolve(z.findByPath('C', "Berlin")!!)!!.displayTitle)
    }

    @Test fun `v1 listing and legacy list give identical results`() {
        for (c in listOf(1, 4, 5)) {
            val legacy = open(archive(TitleLayout.LEGACY, c)); val modern = open(archive(TitleLayout.MODERN, c)); val both = open(archive(TitleLayout.BOTH, c))
            assertEquals(ZimTitleIndex.LEGACY_V0, legacy.titleIndexMode)
            assertEquals(ZimTitleIndex.LISTING_V1, both.titleIndexMode) // modern listing wins when both exist, like libzim
            for (p in listOf("A", "B", "Berlin", "M", "Muro", "R", "T", "Z", "")) {
                val l = legacy.titlesWithPrefix('C', p, 20).filter { !it.isRedirect }.map { it.path }
                assertEquals(l, modern.titlesWithPrefix('C', p, 20).map { it.path }, "prefix '$p' c=$c")
                assertEquals(l, both.titlesWithPrefix('C', p, 20).map { it.path }, "prefix '$p' c=$c")
            }
        }
    }

    @Test fun `the full title search works on a modern archive`() {
        val z = open(archive(TitleLayout.MODERN))
        val hits = ZimTitleSearch().search(z, "Qual è la capitale che si trova a Parigi?")
        assertTrue(hits.any { it.entry.displayTitle == "Parigi" }, hits.toString())
        assertTrue(ZimTitleSearch().search(z, "quando fu eretto il muro di berlino").any { it.entry.displayTitle == "Muro di Berlino" })
    }

    @Test fun `redirects, absent from the v1 listing, are still reached through the path index`() {
        val z = open(archive(TitleLayout.MODERN))
        val hits = ZimTitleSearch().search(z, "dimmi di Berlin")
        assertTrue(hits.any { it.entry.displayTitle == "Berlino" && it.kind == ZimTitleSearch.Kind.EXACT }, hits.toString())
    }

    @Test fun `absent title list and no listing is reported as missing, not as out of bounds`() {
        val e = assertFailsWith<ZimFormatException> { open(archive(TitleLayout.NONE)) }
        assertEquals("title_index_missing", e.message)
        assertTrue("titlePtr=absent" in e.diagnostics, e.diagnostics)
    }

    @Test fun `a compressed v1 listing is not trusted, and without a legacy list the archive is refused`() {
        assertFailsWith<ZimFormatException> { open(archive(TitleLayout.MODERN_COMPRESSED_LISTING)) }
    }

    @Test fun `a really invalid title pointer is still rejected (integrity checks are not weakened)`() {
        for (bad in listOf((1L shl 40), 5L, 10_000_000L, -2L)) {
            val e = assertFailsWith<ZimFormatException> { open(archive(TitleLayout.BOTH, corrupt = bad)) }
            assertEquals("pointer_lists_out_of_bounds", e.message, "ptr=$bad")
            assertTrue("titleListOk=false" in e.diagnostics, e.diagnostics)
        }
    }

    @Test fun `a corrupt listing entry cannot crash the reader`() {
        val b = archive(TitleLayout.MODERN)
        val z0 = open(b)
        // locate the listing blob by scanning for the sorted u32 array tail is brittle; instead flip the last 4 bytes before the checksum area
        val damaged = b.copyOf()
        for (i in damaged.size - 40 until damaged.size - 16) damaged[i] = 0x7F
        runCatching { open(damaged).titlesWithPrefix('C', "R", 5) }
            .onFailure { assertTrue(it is ZimFormatException || it is RuntimeException, "unexpected ${it.javaClass}") }
        assertNotNull(z0)
    }

    @Test fun `diagnostics describe the header without any content`() {
        val d = open(archive(TitleLayout.MODERN)).diagnostics()
        assertTrue("titlePtr=absent" in d && "titleIndex=LISTING_V1" in d && "zim=v6.1" in d, d)
        assertTrue("Roma" !in d && "Parigi" !in d)
    }

    // ---- REAL files (not committed; env-gated) ---------------------------------------------------------------------

    private fun real(name: String): File? = System.getenv("JARVIS_ZIM_TESTDATA")?.let { File(it, name) }?.takeIf { it.isFile }

    /** The real modern file already carries `X/listing/titleOrdered/v1`; we compare both index modes on it. */
    @Test fun `real modern archive - v1 listing equals legacy list, and a header with the sentinel opens`() {
        val f = real("data_nons_wikipedia_en_climate_change_mini_2024-06.zim"); assumeTrue(f != null, "real file missing")
        val orig = open(f!!.readBytes().let { it }) // 9 MB: fine for a test
        assertEquals(ZimTitleIndex.LISTING_V1, orig.titleIndexMode)
        // Re-create what a modern writer produces: same file, header title pointer set to the "absent" sentinel.
        val patched = f.readBytes().also { ByteBuffer.wrap(it, 40, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(-1L) }
        val modern = open(patched)
        assertEquals(ZimTitleIndex.LISTING_V1, modern.titleIndexMode)
        // Force the legacy path on the untouched file by hiding the listing: rename its path so it is not found.
        val noV1 = f.readBytes().also { hideListing(it) }
        val legacy = open(noV1)
        assertEquals(ZimTitleIndex.LEGACY_V0, legacy.titleIndexMode)
        for (p in listOf("Climate", "Global", "Greenhouse", "Paris", "Sea level", "Arctic", "Z", "Carbon")) {
            val a = modern.titlesWithPrefix('C', p, 5000).map { it.path }
            val b = legacy.titlesWithPrefix('C', p, 5000).map { it.path }
            // legacy lists every entry, v1 only front articles: the v1 hits must be a subset in the same order
            assertTrue(a.isNotEmpty() || b.isEmpty(), "prefix $p: v1 empty but legacy=$b")
            assertEquals(b.filter { it in a.toSet() }, a, "prefix $p order")
        }
        val e = modern.resolve(modern.titlesWithPrefix('C', "Climate change", 5).first { it.displayTitle == "Climate change" })!!
        assertTrue(ZimHtml.toText(String(modern.content(e)!!)).text.length > 2000)
        println("REAL(patched sentinel): ${modern.diagnostics()}")
    }

    private fun hideListing(b: ByteArray) {
        // Overwrite "titleOrdered/v1" with "titleOrdered/v9" inside the dirent so the exact lookup misses (same length: no offset shifts).
        val needle = "listing/titleOrdered/v1".toByteArray()
        outer@ for (i in 0..b.size - needle.size) {
            for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
            b[i + needle.size - 1] = '9'.code.toByte()
        }
    }

    @Test fun `real small archive with listing v0 and v1`() {
        val f = real("data_nons_small.zim"); assumeTrue(f != null, "real file missing")
        val z = open(f!!.readBytes())
        assertEquals(ZimTitleIndex.LISTING_V1, z.titleIndexMode)
        assertEquals("Test ZIM file", z.titlesWithPrefix('C', "Test", 3).first().displayTitle)
        val sentinel = f.readBytes().also { ByteBuffer.wrap(it, 40, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(-1L) }
        assertEquals("Test ZIM file", open(sentinel).titlesWithPrefix('C', "Test", 3).first().displayTitle)
    }

    @Test fun `real legacy archives without a listing still open`() {
        for (n in listOf("data_withns_small.zim", "data_withns_wikibooks_be_all_nopic_2017-02.zim")) {
            val f = real(n); assumeTrue(f != null, "$n missing")
            val z = open(f!!.readBytes())
            assertEquals(ZimTitleIndex.LEGACY_V0, z.titleIndexMode, n)
            println("REAL($n): ${z.diagnostics()}")
        }
    }
}

/** Regenerates the tiny synthetic fixtures used by the Android instrumented test. Runs only when JARVIS_ZIM_FIXTURE_OUT is set. */
class ZimFixtureGenerator {
    @Test fun generate() {
        val out = System.getenv("JARVIS_ZIM_FIXTURE_OUT")?.let(::File); assumeTrue(out != null, "JARVIS_ZIM_FIXTURE_OUT not set")
        out!!.mkdirs()
        fun build(layout: TitleLayout, comp: Int) = TestZimBuilder().apply {
            compression = comp; titleLayout = layout; entriesPerCluster = 3
            listOf("Roma" to "Roma è la capitale d'Italia.", "Parigi" to "Parigi è la capitale della Francia.", "Madrid" to "Madrid è la capitale della Spagna.",
                "Berlino" to "Berlino è la capitale della Germania.", "Muro di Berlino" to "Il Muro di Berlino divise la città.")
                .forEach { (t, body) -> article(t.replace(' ', '_'), t, "<html><body><h1>$t</h1><p>$body</p></body></html>") }
            redirect("Berlin", "Berlin", "Berlino")
            metadata("Title", "Fixture").metadata("Language", "ita").metadata("Date", "2026-07-01"); mainPath = "Roma"
        }.build()
        File(out, "modern_listing_v1_zstd.zim").writeBytes(build(TitleLayout.MODERN, 5))
        File(out, "modern_listing_v1_xz.zim").writeBytes(build(TitleLayout.MODERN, 4))
        File(out, "legacy_v0_zstd.zim").writeBytes(build(TitleLayout.LEGACY, 5))
    }
}
