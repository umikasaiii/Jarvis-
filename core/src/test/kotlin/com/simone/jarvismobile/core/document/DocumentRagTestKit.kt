package com.simone.jarvismobile.core.document

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic test documents (nothing personal) and small helpers shared by the BETA 4 tests. */
object DocumentRagTestKit {
    val registry = ParserRegistry.textOnly()

    fun record(id: String, name: String, status: DocumentStatus = DocumentStatus.READY, importedAt: Long = 1, tags: List<String> = emptyList(), title: String? = null) =
        DocumentRecord(
            id = id, fileName = name, displayName = name, originalUri = "content://x/$id", mimeType = "application/pdf",
            fileSize = 10, checksumSha256 = "sum-$id", importedAt = importedAt, modifiedAt = importedAt,
            parserVersion = 1, indexVersion = 1, status = status, tags = tags, title = title,
        )

    /** A paged document, like a PDF: one section per page. */
    fun paged(id: String, name: String, pages: Map<Int, String>): List<DocumentChunk> {
        val parsed = ParsedDocument(
            documentId = id, text = pages.values.joinToString("\n"),
            sections = pages.map { (p, t) -> DocumentSection("", p, t) },
            metadata = ParsedMetadata(title = name.substringBeforeLast('.'), pageCount = pages.size),
        )
        return DocumentChunker.chunk(parsed, name)
    }

    fun parseText(id: String, name: String, mime: String, text: String): List<DocumentChunk> {
        val parser = registry.parserFor(mime, name) ?: error("no parser for $name")
        return DocumentChunker.chunk(parser.parse(id, name, text), name)
    }

    fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    fun blocksOf(hits: List<RetrievedChunk>) = hits.map {
        EvidenceBlock(BlockKind.DOCUMENT, it.citation, it.chunk.text, it.chunk.fileName, it.chunk.page)
    }

    val manutenzione = mapOf(
        7 to "Il motore richiede la sostituzione dell'olio ogni anno. Verificare il livello del liquido refrigerante.",
        8 to "Il prossimo controllo programmato della moto è previsto a 12.000 km. Il controllo include freni e catena di trasmissione.",
        9 to "Garanzia: la copertura vale 24 mesi dalla data di acquisto.",
    )
}
