package com.simone.jarvismobile.core.document

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentFormatsTest {

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // --- HTML ---------------------------------------------------------------

    @Test fun htmlParsesSectionsAndStripsTags() {
        val html = "<h1>Router</h1><p>Serve il reset del dispositivo.</p>" +
            "<h2>Firewall</h2><p>Aprire la porta 8080.</p>"
        val doc = HtmlParser().parse("d", "guida.html", html)
        val titles = doc.sections.map { it.title }
        assertTrue("Router" in titles)
        assertTrue("Firewall" in titles)
        assertTrue("reset del dispositivo" in doc.text)
        assertFalse(doc.text.contains("<"))
    }

    @Test fun htmlDecodesEntities() {
        assertEquals("a & b < c", Html.decodeEntities("a &amp; b &lt; c"))
    }

    // --- EPUB ---------------------------------------------------------------

    @Test fun epubExtractsChaptersInOrder() {
        val epub = zip(
            mapOf(
                "OEBPS/chap1.xhtml" to "<html><body><p>Il reset del router va tenuto premuto.</p></body></html>",
                "OEBPS/chap2.xhtml" to "<html><body><p>La configurazione del firewall.</p></body></html>",
                "META-INF/container.xml" to "<container/>",
            ),
        )
        val doc = OfficeExtractors.extractEpub("d", "libro.epub", epub)
        assertTrue("reset del router" in doc.text)
        assertTrue("firewall" in doc.text.lowercase())
        assertEquals(2, doc.sections.size)
    }

    // --- XLSX ---------------------------------------------------------------

    @Test fun xlsxExtractsRowsWithSharedStrings() {
        val shared = "<sst><si><t>router</t></si><si><t>porta</t></si><si><t>reset</t></si></sst>"
        val sheet = "<worksheet><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>8080</v></c></row>" +
            "</sheetData></worksheet>"
        val xlsx = zip(
            mapOf(
                "xl/sharedStrings.xml" to shared,
                "xl/worksheets/sheet1.xml" to sheet,
            ),
        )
        val doc = OfficeExtractors.extractXlsx("d", "dati.xlsx", xlsx)
        assertTrue("router | porta" in doc.text)
        assertTrue("reset | 8080" in doc.text)
    }

    // --- hybrid rerank ------------------------------------------------------

    @Test fun hybridRerankPrefersFullCoverage() {
        // Candidate A repeats one term a lot (high BM25); B covers both terms.
        val a = DocumentChunk("a", "d", "reset reset reset reset reset", chunkIndex = 0, tokenEstimate = 5, fileName = "d")
        val b = DocumentChunk("b", "d", "per il reset del router premere", chunkIndex = 1, tokenEstimate = 6, fileName = "d")
        val candidates = listOf(RetrievedChunk(a, 1.0), RetrievedChunk(b, 0.7))
        val ranked = HybridReranker.rerank("reset router", candidates, limit = 2)
        assertEquals("b", ranked.first().chunk.id)
    }

    @Test fun hybridRetrieverEndToEnd() {
        val doc = MarkdownParser().parse(
            "d", "ManualeRouter.md",
            "# Reset\nPer il reset del router tenere premuto il tasto.\n\n# Rete\nConfigurazione della rete.",
        )
        val chunks = DocumentChunker.chunk(doc, "ManualeRouter.md")
        val hits = HybridDocumentRetriever(chunks).retrieve("reset del router", topK = 2)
        assertTrue(hits.isNotEmpty())
        assertTrue("reset" in hits.first().chunk.text.lowercase())
    }

    private fun assertFalse(condition: Boolean) = assertTrue(!condition)
}
