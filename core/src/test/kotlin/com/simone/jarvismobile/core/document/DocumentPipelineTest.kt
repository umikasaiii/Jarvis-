package com.simone.jarvismobile.core.document

import com.simone.jarvismobile.core.memory.MemoryRoute
import com.simone.jarvismobile.core.memory.MemoryRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentPipelineTest {

    private val registry = ParserRegistry.textOnly()

    private fun parse(fileName: String, mime: String, text: String): ParsedDocument {
        val parser = registry.parserFor(mime, fileName)
        assertNotNull(parser, "no parser for $fileName")
        return parser.parse("doc:$fileName", fileName, text)
    }

    // --- parsers -------------------------------------------------------------

    @Test fun importsPlainText() {
        val doc = parse("note.txt", "text/plain", "Riga uno.\nRiga due.")
        assertEquals(1, doc.sections.size)
        assertTrue("Riga uno" in doc.text)
    }

    @Test fun importsMarkdownWithSections() {
        val md = """
            # Router
            Il router va riavviato.

            ## Firewall
            Aprire la porta 8080 per il servizio.
        """.trimIndent()
        val doc = parse("appunti.md", "text/markdown", md)
        val titles = doc.sections.map { it.title }
        assertTrue("Router" in titles)
        assertTrue("Firewall" in titles)
        val firewall = doc.sections.first { it.title == "Firewall" }
        assertTrue("8080" in firewall.text)
    }

    @Test fun importsJsonFlattened() {
        val json = """{"device":{"name":"router","port":8080},"tags":["rete","casa"]}"""
        val doc = parse("config.json", "application/json", json)
        assertTrue("device.name: router" in doc.text)
        assertTrue("device.port: 8080" in doc.text)
        assertTrue("tags[0]: rete" in doc.text)
    }

    @Test fun malformedJsonFallsBackToRawText() {
        val doc = parse("broken.json", "application/json", "{not valid")
        assertTrue("{not valid" in doc.text)
    }

    @Test fun importsCsvWithHeaderLabels() {
        val csv = "nome,porta\nrouter,8080\n\"switch, core\",22"
        val doc = parse("hosts.csv", "text/csv", csv)
        assertTrue(doc.sections.any { "nome: router" in it.text && "porta: 8080" in it.text })
        // Quoted field with an embedded comma stays one cell.
        assertTrue(doc.sections.any { "nome: switch, core" in it.text })
    }

    // --- chunking ------------------------------------------------------------

    @Test fun chunkCreationCarriesSourceAndBoundsTokens() {
        val long = (1..400).joinToString(" ") { "parola$it." }
        val md = "# Manuale\n$long"
        val doc = parse("manuale.md", "text/markdown", md)
        val chunks = DocumentChunker.chunk(doc, "manuale.md")
        assertTrue(chunks.size > 1, "a long section should split")
        chunks.forEach {
            assertTrue(it.tokenEstimate <= DocumentChunker.MAX_TOKENS + 1)
            assertEquals("manuale.md", it.fileName)
            assertEquals("Manuale", it.section)
        }
        // chunkIndex is stable and monotonic.
        assertEquals(chunks.indices.toList(), chunks.map { it.chunkIndex })
    }

    @Test fun shortDocumentIsOneChunk() {
        val doc = parse("small.txt", "text/plain", "Solo una frase breve.")
        val chunks = DocumentChunker.chunk(doc, "small.txt")
        assertEquals(1, chunks.size)
    }

    // --- retrieval + source attribution -------------------------------------

    @Test fun retrievalFindsRelevantChunkAndCitesSource() {
        val doc = parse(
            "ManualeRouter.md", "text/markdown",
            """
                # Configurazione
                Per accedere al pannello usare l'indirizzo 192.168.1.1.

                # Ripristino
                La procedura richiede il reset del dispositivo tenendo premuto il tasto.
            """.trimIndent(),
        )
        val chunks = DocumentChunker.chunk(doc, "ManualeRouter.md")
        val retriever = LexicalDocumentRetriever(chunks)
        val hits = DocumentRetrieval.select("come faccio il reset del dispositivo", retriever)
        assertTrue(hits.isNotEmpty())
        val top = hits.first()
        assertTrue("reset" in top.chunk.text.lowercase())
        assertTrue("ManualeRouter.md" in top.citation)
        assertTrue("Ripristino" in top.citation)
    }

    @Test fun retrievalReturnsNothingWhenOffTopic() {
        val doc = parse("cucina.txt", "text/plain", "La ricetta della carbonara usa uova e guanciale.")
        val chunks = DocumentChunker.chunk(doc, "cucina.txt")
        val retriever = LexicalDocumentRetriever(chunks)
        val hits = retriever.retrieve("configurazione firewall del router aziendale")
        assertTrue(hits.isEmpty(), "an unrelated question must not match")
    }

    @Test fun contextBudgetCapsChunkCount() {
        val big = (1..50).joinToString("\n\n") { "# Sezione $it\n" + "reset ".repeat(200) }
        val doc = parse("grande.md", "text/markdown", big)
        val chunks = DocumentChunker.chunk(doc, "grande.md")
        val retriever = LexicalDocumentRetriever(chunks)
        val hits = DocumentRetrieval.select("reset", retriever)
        assertTrue(hits.size <= DocumentRetrieval.MAX_CONTEXT)
    }

    @Test fun groundedContextTagsEverySource() {
        val doc = parse("a.md", "text/markdown", "# T\nreset del router necessario.")
        val chunks = DocumentChunker.chunk(doc, "a.md")
        val hits = LexicalDocumentRetriever(chunks).retrieve("reset router")
        val block = DocumentRetrieval.groundedContext(hits)
        assertTrue(block.startsWith("[Fonte:"))
        assertTrue("a.md" in block)
    }

    // --- duplicate detection & safe names ------------------------------------

    @Test fun sameBytesProduceSameChecksum() {
        val a = Checksums.sha256("contenuto identico".toByteArray())
        val b = Checksums.sha256("contenuto identico")
        val c = Checksums.sha256("contenuto diverso")
        assertEquals(a, b)
        assertFalse(a == c)
        assertEquals(64, a.length)
    }

    @Test fun safeFileNameStripsTraversalAndSeparators() {
        val safe = SafeFileName.of("../../etc/pass word.txt")
        assertFalse("/" in safe)
        assertFalse(".." in safe)
        assertFalse(" " in safe)
        assertTrue(safe.endsWith(".txt"))
    }

    @Test fun uniqueNameAddsChecksumSuffix() {
        val n1 = SafeFileName.uniqueOf("report.pdf", "abcdef1234567890")
        val n2 = SafeFileName.uniqueOf("report.pdf", "0987654321fedcba")
        assertFalse(n1 == n2)
        assertTrue(n1.endsWith(".pdf"))
        assertTrue("abcdef12" in n1)
    }

    @Test fun blankNameFallsBack() {
        assertEquals("documento", SafeFileName.of("///"))
    }

    // --- corrupted / empty input --------------------------------------------

    @Test fun emptyContentDoesNotCrash() {
        val doc = parse("empty.txt", "text/plain", "")
        val chunks = DocumentChunker.chunk(doc, "empty.txt")
        assertTrue(chunks.isEmpty() || chunks.all { it.text.isBlank() })
    }

    // --- memory router -------------------------------------------------------

    @Test fun routerArithmeticIsNone() {
        assertEquals(MemoryRoute.NONE, MemoryRouter.classify("quanto fa 2+2?"))
    }

    @Test fun routerDocumentQuestion() {
        assertEquals(
            MemoryRoute.DOCUMENT_SEARCH,
            MemoryRouter.classify("cosa dice il PDF che ho caricato sul router?"),
        )
    }

    @Test fun routerAttachedDocumentDeictic() {
        assertEquals(
            MemoryRoute.DOCUMENT_SEARCH,
            MemoryRouter.classify("riassumi questo file", hasAttachedDocuments = true),
        )
    }

    @Test fun routerPersonalMemory() {
        assertEquals(
            MemoryRoute.PERSONAL_MEMORY,
            MemoryRouter.classify("qual era la configurazione decisa per il TTS?"),
        )
    }

    @Test fun routerCoreMemory() {
        assertEquals(
            MemoryRoute.CORE_MEMORY,
            MemoryRouter.classify("come mi piace impostare JARVIS?"),
        )
    }

    @Test fun routerPlainQuestionIsNone() {
        assertEquals(MemoryRoute.NONE, MemoryRouter.classify("ciao come stai oggi"))
    }
}
