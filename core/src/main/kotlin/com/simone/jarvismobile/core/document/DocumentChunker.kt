package com.simone.jarvismobile.core.document

/**
 * Splits a [ParsedDocument] into overlapping, section-aware [DocumentChunk]s
 * sized for a local model's context.
 *
 * The whole document is never sent to the model — only the few chunks a query
 * retrieves. Chunks target ~400–800 tokens with a small overlap so a fact split
 * across a boundary is still found, and every chunk keeps its document title,
 * section and page so a hit can be cited.
 *
 * Token counts are estimated at ~4 characters per token (a good enough proxy for
 * Italian/English prose without bundling a tokenizer).
 */
object DocumentChunker {

    const val CHARS_PER_TOKEN = 4
    const val TARGET_TOKENS = 600
    const val MAX_TOKENS = 800
    const val MIN_TOKENS = 60
    const val OVERLAP_TOKENS = 80

    private const val TARGET_CHARS = TARGET_TOKENS * CHARS_PER_TOKEN
    private const val MAX_CHARS = MAX_TOKENS * CHARS_PER_TOKEN
    private const val OVERLAP_CHARS = OVERLAP_TOKENS * CHARS_PER_TOKEN
    private const val MIN_CHARS = MIN_TOKENS * CHARS_PER_TOKEN

    fun chunk(parsed: ParsedDocument, fileName: String = ""): List<DocumentChunk> {
        val title = parsed.metadata.title ?: fileName.substringBeforeLast('.')
        val out = ArrayList<DocumentChunk>()
        var index = 0

        for (section in parsed.sections) {
            val pieces = splitBody(section.text)
            for (piece in pieces) {
                if (piece.isBlank()) continue
                out += DocumentChunk(
                    id = "${parsed.documentId}#$index",
                    documentId = parsed.documentId,
                    text = piece,
                    section = section.title,
                    page = section.page,
                    chunkIndex = index,
                    tokenEstimate = estimateTokens(piece),
                    documentTitle = title,
                    fileName = fileName,
                )
                index++
            }
        }

        // Fold a trailing scrap into its predecessor so no chunk is too thin to
        // carry meaning (unless it is the only chunk).
        return foldTinyTail(out)
    }

    fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /**
     * Splits one section body into ~target-sized pieces on sentence boundaries,
     * carrying [OVERLAP_CHARS] of the previous piece into the next.
     */
    private fun splitBody(body: String): List<String> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()
        if (text.length <= MAX_CHARS) return listOf(text)

        val sentences = text.split(Regex("""(?<=[.!?。！？])\s+"""))
        val pieces = ArrayList<String>()
        val current = StringBuilder()

        fun commit() {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) pieces += piece
            // Seed the next piece with a tail overlap of the one just committed.
            val tail = piece.takeLast(OVERLAP_CHARS)
            current.setLength(0)
            if (tail.isNotEmpty() && piece.length > OVERLAP_CHARS) {
                val cut = tail.indexOf(' ').let { if (it in 0 until tail.length - 1) it + 1 else 0 }
                current.append(tail.substring(cut)).append(' ')
            }
        }

        for (sentence in sentences) {
            if (current.length + sentence.length > TARGET_CHARS && current.isNotBlank()) commit()
            if (sentence.length > MAX_CHARS) {
                // A single monster sentence: hard-cut on whitespace.
                var rest = sentence
                while (rest.length > MAX_CHARS) {
                    val cut = rest.lastIndexOf(' ', MAX_CHARS).takeIf { it > 0 } ?: MAX_CHARS
                    current.append(rest.substring(0, cut))
                    commit()
                    rest = rest.substring(cut).trim()
                }
                current.append(rest).append(' ')
            } else {
                current.append(sentence).append(' ')
            }
        }
        val last = current.toString().trim()
        if (last.isNotEmpty()) pieces += last
        return pieces
    }

    private fun foldTinyTail(chunks: List<DocumentChunk>): List<DocumentChunk> {
        if (chunks.size <= 1) return chunks
        val last = chunks.last()
        val prev = chunks[chunks.size - 2]
        if (last.text.length < MIN_CHARS && last.section == prev.section &&
            prev.text.length + last.text.length <= MAX_CHARS
        ) {
            val merged = prev.copy(
                text = prev.text + "\n" + last.text,
                tokenEstimate = estimateTokens(prev.text + "\n" + last.text),
            )
            return chunks.dropLast(2) + merged
        }
        return chunks
    }
}
