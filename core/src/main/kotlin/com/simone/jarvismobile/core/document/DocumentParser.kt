package com.simone.jarvismobile.core.document

/**
 * Turns a document's raw content into structured [ParsedDocument] text.
 *
 * Text-native formats (txt, md, json, csv) are parsed here, in pure Kotlin, so
 * they are unit-tested off-device. Binary formats (pdf, docx, later epub/xlsx…)
 * need platform libraries to extract their text, so their parsers live in the
 * Android layer but implement this same interface and produce the same
 * [ParsedDocument] — the chunker and retriever never learn the difference.
 */
interface DocumentParser {
    /** Bumped when the parser's output changes, so records can be re-parsed. */
    val version: Int

    /** Stable id for logs/records, e.g. "markdown". */
    val id: String

    /** True if this parser can handle the given mime type / file name. */
    fun supports(mimeType: String, fileName: String): Boolean

    /** Parses already-decoded [text] into structured sections. */
    fun parse(documentId: String, fileName: String, text: String): ParsedDocument
}

private fun ext(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

/** `.txt` and anything unknown-but-textual: the whole body, one section. */
class PlainTextParser : DocumentParser {
    override val version = 1
    override val id = "text"
    override fun supports(mimeType: String, fileName: String): Boolean {
        val e = ext(fileName)
        return e == "txt" || e == "text" || e == "log" || mimeType.startsWith("text/plain")
    }

    override fun parse(documentId: String, fileName: String, text: String): ParsedDocument {
        val body = text.trim()
        val title = fileName.substringBeforeLast('.').ifBlank { fileName }
        return ParsedDocument(
            documentId = documentId,
            text = body,
            sections = listOf(DocumentSection(title = "", page = null, text = body)),
            metadata = ParsedMetadata(title = title),
        )
    }
}

/** Markdown: split on ATX headings so each `#`/`##` becomes a citable section. */
class MarkdownParser : DocumentParser {
    override val version = 1
    override val id = "markdown"
    private val heading = Regex("""^(#{1,6})\s+(.*\S)\s*$""")

    override fun supports(mimeType: String, fileName: String): Boolean {
        val e = ext(fileName)
        return e == "md" || e == "markdown" || mimeType == "text/markdown"
    }

    override fun parse(documentId: String, fileName: String, text: String): ParsedDocument {
        val sections = ArrayList<DocumentSection>()
        var current = ""
        val buffer = StringBuilder()
        var docTitle: String? = null

        fun flush() {
            val body = buffer.toString().trim()
            buffer.setLength(0)
            if (body.isNotEmpty()) sections += DocumentSection(title = current, page = null, text = body)
        }

        for (raw in text.lineSequence()) {
            val h = heading.find(raw.trim())
            if (h != null) {
                flush()
                current = h.groupValues[2]
                if (docTitle == null && h.groupValues[1].length == 1) docTitle = current
                continue
            }
            buffer.append(raw).append('\n')
        }
        flush()
        if (sections.isEmpty()) sections += DocumentSection("", null, text.trim())
        return ParsedDocument(
            documentId = documentId,
            text = text.trim(),
            sections = sections,
            metadata = ParsedMetadata(title = docTitle ?: fileName.substringBeforeLast('.')),
        )
    }
}

/**
 * JSON: flattened into readable `path: value` lines so keys and values are
 * searchable without a schema. Structure is preserved as dotted paths; arrays
 * are indexed. Deliberately dependency-free (a tiny recursive-descent reader).
 */
class JsonParser : DocumentParser {
    override val version = 1
    override val id = "json"
    override fun supports(mimeType: String, fileName: String): Boolean =
        ext(fileName) == "json" || mimeType == "application/json"

    override fun parse(documentId: String, fileName: String, text: String): ParsedDocument {
        val lines = ArrayList<String>()
        runCatching {
            val value = JsonMini.parse(text)
            JsonMini.flatten("", value, lines)
        }.onFailure {
            // Not valid JSON: fall back to the raw text so nothing is lost.
            lines += text.trim()
        }
        val body = lines.joinToString("\n")
        return ParsedDocument(
            documentId = documentId,
            text = body,
            sections = listOf(DocumentSection("", null, body)),
            metadata = ParsedMetadata(title = fileName.substringBeforeLast('.')),
        )
    }
}

/**
 * CSV: the header row labels each cell so a row reads as `col: value; col: value`.
 * Handles quoted fields and embedded commas. Each row becomes its own section so
 * a hit can cite the row it came from.
 */
class CsvParser : DocumentParser {
    override val version = 1
    override val id = "csv"
    override fun supports(mimeType: String, fileName: String): Boolean {
        val e = ext(fileName)
        return e == "csv" || e == "tsv" || mimeType == "text/csv"
    }

    override fun parse(documentId: String, fileName: String, text: String): ParsedDocument {
        val delimiter = if (ext(fileName) == "tsv") '\t' else ','
        val rows = splitRows(text).map { parseLine(it, delimiter) }
        if (rows.isEmpty()) {
            return ParsedDocument(documentId, "", listOf(DocumentSection("", null, "")))
        }
        val header = rows.first()
        val sections = ArrayList<DocumentSection>()
        val allText = StringBuilder()
        rows.drop(1).forEachIndexed { i, cells ->
            val line = cells.mapIndexed { c, v ->
                val key = header.getOrNull(c)?.trim().orEmpty().ifBlank { "col${c + 1}" }
                "$key: ${v.trim()}"
            }.joinToString("; ")
            sections += DocumentSection(title = "riga ${i + 1}", page = null, text = line)
            allText.append(line).append('\n')
        }
        return ParsedDocument(
            documentId = documentId,
            text = allText.toString().trim(),
            sections = sections.ifEmpty { listOf(DocumentSection("", null, header.joinToString(", "))) },
            metadata = ParsedMetadata(title = fileName.substringBeforeLast('.')),
        )
    }

    private fun splitRows(text: String): List<String> =
        text.split(Regex("\r\n|\r|\n")).filter { it.isNotBlank() }

    private fun parseLine(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}

/**
 * Picks the right parser for a file. New formats register here (or, for binary
 * formats, in the Android layer which appends its parsers before building this).
 */
class ParserRegistry(private val parsers: List<DocumentParser>) {
    fun parserFor(mimeType: String, fileName: String): DocumentParser? =
        parsers.firstOrNull { it.supports(mimeType, fileName) }

    fun canParse(mimeType: String, fileName: String): Boolean = parserFor(mimeType, fileName) != null

    companion object {
        /** The dependency-free text parsers usable anywhere, incl. unit tests. */
        fun textOnly(): ParserRegistry =
            ParserRegistry(listOf(MarkdownParser(), JsonParser(), CsvParser(), PlainTextParser()))
    }
}
