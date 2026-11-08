package com.simone.jarvismobile.document

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.simone.jarvismobile.background.JarvisDatabase
import com.simone.jarvismobile.core.document.DocumentChunker
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentRetrieval
import com.simone.jarvismobile.core.document.DocumentSource
import com.simone.jarvismobile.core.document.DocumentStatus
import com.simone.jarvismobile.core.document.HybridDocumentRetriever
import com.simone.jarvismobile.core.document.ImportFailure
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * BETA 4 — REAL extraction (PDFBox, DOCX zip) and the REAL Room index, on synthetic documents
 * generated inside the test (nothing personal). Runs on the emulator workflow; main CI only compiles it.
 */
@RunWith(AndroidJUnit4::class)
class DocumentPipelineInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "beta4_docs_test.db"
    private lateinit var db: JarvisDatabase

    @Before fun setUp() {
        PDFBoxResourceLoader.init(context)
        context.deleteDatabase(dbName)
        db = open()
    }

    @After fun tearDown() {
        runCatching { db.close() }
        context.deleteDatabase(dbName)
    }

    private fun open() = Room.databaseBuilder(context, JarvisDatabase::class.java, dbName).allowMainThreadQueries().build()

    private fun textPdf(vararg pages: String): ByteArray {
        val doc = PDDocument()
        pages.forEach { text ->
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(50f, 700f); cs.showText(text); cs.endText()
            }
        }
        return ByteArrayOutputStream().also { doc.save(it); doc.close() }.toByteArray()
    }

    private fun scannedPdf(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(); doc.addPage(page)
        PDPageContentStream(doc, page).use { cs -> cs.addRect(50f, 50f, 200f, 200f); cs.fill() } // drawing only, no text
        return ByteArrayOutputStream().also { doc.save(it); doc.close() }.toByteArray()
    }

    private fun docx(body: String): ByteArray {
        val xml = "<w:document><w:body><w:p><w:r><w:t>$body</w:t></w:r></w:p></w:body></w:document>"
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry() }
        }.toByteArray()
    }

    private fun record(id: String, name: String, status: DocumentStatus = DocumentStatus.READY, tags: List<String> = emptyList()) =
        DocumentRecord(
            id = id, fileName = name, displayName = name, originalUri = "content://test/$id", mimeType = "application/pdf",
            fileSize = 1, checksumSha256 = "sum-$id", importedAt = 1, modifiedAt = 1, parserVersion = 1, indexVersion = 1,
            status = status, tags = tags, source = DocumentSource.VAULT_IMPORT,
        )

    @Test fun textPdfIsExtractedPageByPageAndTheRightPageIsFound() {
        val bytes = textPdf("Introduzione generale", "Il prossimo controllo della moto e previsto a 12000 km", "Garanzia di ventiquattro mesi")
        val parsed = DocumentTextExtractors.extract(context, "d1", "Manutenzione.pdf", "application/pdf", bytes)
        assertTrue(parsed.text.contains("12000"))
        assertEquals(3, parsed.metadata.pageCount)
        val chunks = DocumentChunker.chunk(parsed, "Manutenzione.pdf")
        val hit = DocumentRetrieval.select("quando il prossimo controllo della moto", HybridDocumentRetriever(chunks)).first()
        assertEquals(2, hit.chunk.page)
        assertEquals("Manutenzione.pdf — pagina 2", hit.citation)
    }

    @Test fun scannedPdfIsRecognisedAsAScanNotAsEmptyText() {
        val parsed = DocumentTextExtractors.extract(context, "d2", "Scansione.pdf", "application/pdf", scannedPdf())
        assertTrue(parsed.text.isBlank())
        assertEquals(DocumentTextExtractors.SCANNED_PDF, parsed.metadata.extra[DocumentTextExtractors.EXTRA_KEY])
        assertEquals(1, parsed.metadata.pageCount)
    }

    @Test fun aDamagedPdfIsReportedCorruptAndNeverThrows() {
        val parsed = DocumentTextExtractors.extract(context, "d3", "Rotto.pdf", "application/pdf", "%PDF-1.4 this is not really a pdf".toByteArray())
        assertTrue(parsed.text.isBlank())
        assertEquals(DocumentTextExtractors.CORRUPT, parsed.metadata.extra[DocumentTextExtractors.EXTRA_KEY])
    }

    @Test fun docxTextIsExtractedAndSearchable() {
        val parsed = DocumentTextExtractors.extract(context, "d4", "Lettera.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx("La polizza scade il trentuno marzo"))
        assertTrue(parsed.text.contains("polizza scade"))
        val hits = DocumentRetrieval.select("quando scade la polizza", HybridDocumentRetriever(DocumentChunker.chunk(parsed, "Lettera.docx")))
        assertTrue(hits.isNotEmpty())
    }

    @Test fun aZipThatIsNotAWordFileIsCorrupt() {
        val notWord = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write("x".toByteArray()); z.closeEntry() } }.toByteArray()
        val parsed = DocumentTextExtractors.extract(context, "d5", "Falso.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", notWord)
        assertEquals(DocumentTextExtractors.CORRUPT, parsed.metadata.extra[DocumentTextExtractors.EXTRA_KEY])
    }

    @Test fun theIndexSurvivesClosingAndReopeningTheDatabase() = runBlocking {
        val dao = db.documentDao()
        val parsed = DocumentTextExtractors.extract(context, "p1", "Manuale.pdf", "application/pdf", textPdf("Cambio olio ogni 6000 km"))
        val chunks = DocumentChunker.chunk(parsed, "Manuale.pdf")
        dao.upsertDocument(record("p1", "Manuale.pdf").toEntity())
        dao.upsertChunks(chunks.map { it.toEntity() })
        db.close()
        db = open()
        val back = db.documentDao().readyChunks().map { it.toChunk() }
        assertEquals(chunks.map { it.id }.toSet(), back.map { it.id }.toSet())
        val hit = DocumentRetrieval.select("cambio olio", HybridDocumentRetriever(back)).first()
        assertEquals(1, hit.chunk.page)
    }

    @Test fun deletingADocumentRemovesItsPassagesFromTheIndex() = runBlocking {
        val dao = db.documentDao()
        val c = DocumentChunker.chunk(DocumentTextExtractors.extract(context, "p2", "A.pdf", "application/pdf", textPdf("contenuto da eliminare")), "A.pdf")
        dao.upsertDocument(record("p2", "A.pdf").toEntity()); dao.upsertChunks(c.map { it.toEntity() })
        dao.deleteChunks("p2"); dao.deleteDocument("p2")
        assertTrue(dao.readyChunks().isEmpty())
        assertNull(dao.findById("p2"))
    }

    @Test fun onlyReadyDocumentsAreSearchableAndAFailedOneKeepsItsReason() = runBlocking {
        val dao = db.documentDao()
        dao.upsertDocument(record("ok", "Ok.pdf").toEntity())
        dao.upsertChunks(DocumentChunker.chunk(DocumentTextExtractors.extract(context, "ok", "Ok.pdf", "application/pdf", textPdf("testo valido")), "Ok.pdf").map { it.toEntity() })
        dao.upsertDocument(record("bad", "Scan.pdf", DocumentStatus.FAILED, listOf(ImportFailure.SCANNED_PDF.tag)).toEntity())
        assertTrue(dao.readyChunks().all { it.documentId == "ok" })
        val failed = dao.findById("bad")!!.toRecord()
        assertEquals(DocumentStatus.FAILED, failed.status)
        assertEquals(ImportFailure.SCANNED_PDF, ImportFailure.fromTags(failed.tags))
        assertFalse(failed.isReady)
    }

    @Test fun duplicateChecksumIsDetectable() = runBlocking {
        val dao = db.documentDao()
        dao.upsertDocument(record("x1", "X.pdf").toEntity())
        assertEquals("x1", dao.findByChecksum("sum-x1")?.id)
        assertNull(dao.findByChecksum("sum-other"))
    }
}
