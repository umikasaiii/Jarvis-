package com.simone.jarvismobile.document

import android.content.Context
import android.util.Log
import com.simone.jarvismobile.core.document.DocumentSection
import com.simone.jarvismobile.core.document.ParsedDocument
import com.simone.jarvismobile.core.document.ParsedMetadata
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts text from the binary formats that need platform code (PDF, DOCX) into
 * the same [ParsedDocument] the pure-Kotlin parsers produce, so chunking and
 * retrieval are format-blind. Everything here is on-device; nothing touches the
 * network. Extraction is bounded and never throws to the caller — a failure
 * returns an empty document and the import is marked FAILED upstream.
 */
object DocumentTextExtractors {

    private const val TAG = "JarvisDocExtract"
    const val PARSER_VERSION = 1

    /** Recognised binary formats. Text formats are handled by the core registry. */
    fun isBinary(mimeType: String, fileName: String): Boolean =
        isPdf(mimeType, fileName) || isDocx(mimeType, fileName)

    fun isPdf(mimeType: String, fileName: String): Boolean =
        mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true)

    fun isDocx(mimeType: String, fileName: String): Boolean =
        mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            fileName.endsWith(".docx", ignoreCase = true)

    fun extract(context: Context, documentId: String, fileName: String, mimeType: String, bytes: ByteArray): ParsedDocument =
        when {
            isPdf(mimeType, fileName) -> extractPdf(context, documentId, fileName, bytes)
            isDocx(mimeType, fileName) -> extractDocx(documentId, fileName, bytes)
            else -> ParsedDocument(documentId, "", listOf(DocumentSection("", null, "")))
        }

    /** PDF: one [DocumentSection] per page so a hit cites a page number. */
    private fun extractPdf(context: Context, documentId: String, fileName: String, bytes: ByteArray): ParsedDocument {
        return runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
                val stripper = PDFTextStripper()
                val pages = doc.numberOfPages
                val sections = ArrayList<DocumentSection>(pages)
                val all = StringBuilder()
                for (page in 1..pages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = runCatching { stripper.getText(doc) }.getOrDefault("").trim()
                    if (text.isNotEmpty()) {
                        sections += DocumentSection(title = "", page = page, text = text)
                        all.append(text).append('\n')
                    }
                }
                val info = doc.documentInformation
                ParsedDocument(
                    documentId = documentId,
                    text = all.toString().trim(),
                    sections = sections.ifEmpty { listOf(DocumentSection("", null, "")) },
                    metadata = ParsedMetadata(
                        title = info?.title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
                        author = info?.author?.takeIf { it.isNotBlank() },
                        pageCount = pages,
                    ),
                )
            }
        }.getOrElse {
            Log.w(TAG, "pdf_extract_failed ${it.javaClass.simpleName}")
            ParsedDocument(documentId, "", listOf(DocumentSection("", null, "")))
        }
    }

    /**
     * DOCX: a zip whose `word/document.xml` holds the body. Paragraphs (`<w:p>`)
     * become newlines and all tags are stripped — enough to make the prose
     * searchable without an Office library.
     */
    private fun extractDocx(documentId: String, fileName: String, bytes: ByteArray): ParsedDocument {
        return runCatching {
            var xml: String? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        xml = zip.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                }
            }
            val body = xml?.let { docxXmlToText(it) }.orEmpty().trim()
            ParsedDocument(
                documentId = documentId,
                text = body,
                sections = listOf(DocumentSection("", null, body)),
                metadata = ParsedMetadata(title = fileName.substringBeforeLast('.')),
            )
        }.getOrElse {
            Log.w(TAG, "docx_extract_failed ${it.javaClass.simpleName}")
            ParsedDocument(documentId, "", listOf(DocumentSection("", null, "")))
        }
    }

    private fun docxXmlToText(xml: String): String = xml
        // Paragraph and line breaks become real newlines before tags are stripped.
        .replace(Regex("</w:p>"), "\n")
        .replace(Regex("<w:br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}
