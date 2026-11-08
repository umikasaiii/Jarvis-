package com.simone.jarvismobile.core.document

enum class DocumentKind { PDF, DOCX, EPUB, XLSX, MARKDOWN, TEXT, HTML, CSV, JSON, IMAGE }

/**
 * What the importer accepts, and the size ceiling, in one pure place (BETA 4). The limit is
 * deliberately NOT raised: a file is read fully into memory before parsing, and on a phone a
 * 25 MB PDF already costs several times that while PDFBox extracts it.
 */
object DocumentFormatPolicy {
    const val MAX_FILE_BYTES: Long = 25L * 1024 * 1024
    const val LIMIT_LABEL = "25 MB"

    private val byExtension = mapOf(
        "pdf" to DocumentKind.PDF, "docx" to DocumentKind.DOCX, "epub" to DocumentKind.EPUB,
        "xlsx" to DocumentKind.XLSX, "md" to DocumentKind.MARKDOWN, "markdown" to DocumentKind.MARKDOWN,
        "txt" to DocumentKind.TEXT, "text" to DocumentKind.TEXT, "log" to DocumentKind.TEXT,
        "html" to DocumentKind.HTML, "htm" to DocumentKind.HTML, "csv" to DocumentKind.CSV,
        "json" to DocumentKind.JSON,
        "jpg" to DocumentKind.IMAGE, "jpeg" to DocumentKind.IMAGE, "png" to DocumentKind.IMAGE,
        "webp" to DocumentKind.IMAGE, "heic" to DocumentKind.IMAGE, "bmp" to DocumentKind.IMAGE,
    )

    fun kindOf(fileName: String, mimeType: String): DocumentKind? {
        byExtension[fileName.substringAfterLast('.', "").lowercase()]?.let { return it }
        val m = mimeType.lowercase()
        return when {
            m == "application/pdf" -> DocumentKind.PDF
            m.startsWith("image/") -> DocumentKind.IMAGE
            m == "text/markdown" -> DocumentKind.MARKDOWN
            m == "text/html" -> DocumentKind.HTML
            m == "text/csv" -> DocumentKind.CSV
            m == "application/json" -> DocumentKind.JSON
            m.startsWith("text/") -> DocumentKind.TEXT
            else -> null
        }
    }

    /** Checks the metadata only (before reading any byte). A declared size of 0 means "unknown". */
    fun checkMetadata(fileName: String, mimeType: String, declaredSize: Long): ImportFailure? = when {
        kindOf(fileName, mimeType) == null -> ImportFailure.UNSUPPORTED
        declaredSize > MAX_FILE_BYTES -> ImportFailure.TOO_LARGE
        else -> null
    }

    /** Checks the real bytes: emptiness, size, and that the content matches the declared format. */
    fun checkBytes(kind: DocumentKind, bytes: ByteArray): ImportFailure? {
        if (bytes.isEmpty()) return ImportFailure.EMPTY
        if (bytes.size > MAX_FILE_BYTES) return ImportFailure.TOO_LARGE
        return when (kind) {
            DocumentKind.PDF -> if (startsWith(bytes, "%PDF-")) null else ImportFailure.CORRUPT
            DocumentKind.DOCX, DocumentKind.EPUB, DocumentKind.XLSX -> if (startsWith(bytes, "PK")) null else ImportFailure.CORRUPT
            DocumentKind.IMAGE -> null
            else -> if (looksBinary(bytes)) ImportFailure.CORRUPT else null
        }
    }

    /** True when the first 4 KB contain NUL bytes — a text format that is really a binary blob. */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 4096)
        if (n == 0) return false
        var nul = 0
        for (i in 0 until n) if (bytes[i].toInt() == 0) nul++
        return nul > 0
    }

    /**
     * Reads at most [MAX_FILE_BYTES] + 1 bytes: enough to tell "too large" without ever holding a
     * huge file in memory. Returns null if the stream could not be read.
     */
    fun readBounded(read: (maxBytes: Int) -> ByteArray?): ByteArray? = read((MAX_FILE_BYTES + 1).toInt())

    private fun startsWith(bytes: ByteArray, prefix: String): Boolean {
        if (bytes.size < prefix.length) return false
        for (i in prefix.indices) if (bytes[i].toInt() != prefix[i].code) return false
        return true
    }
}
