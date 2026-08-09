package com.simone.jarvismobile.core.document

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Text extraction for the container formats that are really ZIPs of XML/XHTML —
 * EPUB and XLSX. `java.util.zip` is part of the JVM/Android platform, so these
 * live in `:core` and are unit-tested off-device, exactly like the text parsers.
 * A failure returns an empty [ParsedDocument]; nothing throws to the caller.
 */
object OfficeExtractors {

    const val PARSER_VERSION = 1

    fun isEpub(mimeType: String, fileName: String): Boolean =
        mimeType == "application/epub+zip" || fileName.endsWith(".epub", ignoreCase = true)

    fun isXlsx(mimeType: String, fileName: String): Boolean =
        mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            fileName.endsWith(".xlsx", ignoreCase = true)

    fun isContainer(mimeType: String, fileName: String): Boolean =
        isEpub(mimeType, fileName) || isXlsx(mimeType, fileName)

    fun extract(documentId: String, fileName: String, mimeType: String, bytes: ByteArray): ParsedDocument = when {
        isEpub(mimeType, fileName) -> extractEpub(documentId, fileName, bytes)
        isXlsx(mimeType, fileName) -> extractXlsx(documentId, fileName, bytes)
        else -> ParsedDocument(documentId, "", listOf(DocumentSection("", null, "")))
    }

    // --- EPUB: a zip of XHTML chapters -------------------------------------

    fun extractEpub(documentId: String, fileName: String, bytes: ByteArray): ParsedDocument {
        val chapters = sortedMapOf<String, String>()
        runCatching {
            forEachEntry(bytes) { name, read ->
                if (name.endsWith(".xhtml", true) || name.endsWith(".html", true) || name.endsWith(".htm", true)) {
                    chapters[name] = Html.toText(read().toString(Charsets.UTF_8))
                }
            }
        }
        val sections = chapters.entries
            .filter { it.value.isNotBlank() }
            .mapIndexed { i, e -> DocumentSection(title = "Capitolo ${i + 1}", page = null, text = e.value) }
        val body = sections.joinToString("\n\n") { it.text }
        return ParsedDocument(
            documentId = documentId,
            text = body.trim(),
            sections = sections.ifEmpty { listOf(DocumentSection("", null, "")) },
            metadata = ParsedMetadata(title = fileName.substringBeforeLast('.')),
        )
    }

    // --- XLSX: shared strings + one section per sheet ----------------------

    fun extractXlsx(documentId: String, fileName: String, bytes: ByteArray): ParsedDocument {
        val shared = ArrayList<String>()
        val sheets = sortedMapOf<String, String>()
        runCatching {
            forEachEntry(bytes) { name, read ->
                when {
                    name == "xl/sharedStrings.xml" ->
                        shared += sharedStrings(read().toString(Charsets.UTF_8))
                    name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") ->
                        sheets[name] = read().toString(Charsets.UTF_8)
                }
            }
        }
        val sections = ArrayList<DocumentSection>()
        val allText = StringBuilder()
        var index = 1
        for ((_, xml) in sheets) {
            val text = sheetText(xml, shared)
            if (text.isNotBlank()) {
                sections += DocumentSection(title = "Foglio $index", page = null, text = text)
                allText.append(text).append('\n')
            }
            index++
        }
        return ParsedDocument(
            documentId = documentId,
            text = allText.toString().trim(),
            sections = sections.ifEmpty { listOf(DocumentSection("", null, "")) },
            metadata = ParsedMetadata(title = fileName.substringBeforeLast('.')),
        )
    }

    /** Reads `<si>` shared strings in document order (indexable by cell refs). */
    private fun sharedStrings(xml: String): List<String> =
        Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { Html.toText(it.groupValues[1]).replace('\n', ' ').trim() }
            .toList()

    /**
     * Turns a worksheet into `value | value | …` rows. A cell of type `s` indexes
     * [shared]; inline/number cells use their own `<v>`. Enough to make the data
     * searchable without a spreadsheet engine.
     */
    private fun sheetText(xml: String, shared: List<String>): String {
        val rows = Regex("<row[ >].*?</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        val out = StringBuilder()
        for (row in rows) {
            val cells = Regex("<c[ >].*?</c>|<c[^>]*/>", RegexOption.DOT_MATCHES_ALL)
                .findAll(row.value)
                .map { cell ->
                    val isShared = Regex("""t="s"""").containsMatchIn(cell.value)
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(cell.value)
                        ?.groupValues?.get(1)?.trim().orEmpty()
                    when {
                        v.isEmpty() -> ""
                        isShared -> v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        else -> Html.decodeEntities(v)
                    }
                }
                .toList()
            if (cells.any { it.isNotBlank() }) out.append(cells.joinToString(" | ") { it.trim() }).append('\n')
        }
        return out.toString().trim()
    }

    private inline fun forEachEntry(bytes: ByteArray, block: (name: String, read: () -> ByteArray) -> Unit) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                block(name) { zip.readBytes() }
            }
        }
    }
}

/** Minimal HTML/XHTML helpers: strip tags and decode the common entities. */
object Html {
    fun toText(html: String): String = html
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?i)</(p|div|li|h[1-6]|tr|br)\\s*>"), "\n")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .let(::decodeEntities)
        .lineSequence().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }
        .joinToString("\n")

    fun decodeEntities(text: String): String = text
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
}
