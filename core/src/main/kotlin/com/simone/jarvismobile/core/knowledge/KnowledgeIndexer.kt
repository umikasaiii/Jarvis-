package com.simone.jarvismobile.core.knowledge

/**
 * Splits a document into passages small enough to fit a 4096-token context
 * alongside the conversation, while keeping the heading each passage came from
 * so JARVIS can cite where an answer comes from.
 *
 * Pure Kotlin and unit-tested: chunking decides what the model can ever see, so
 * a silent bug here would look like the assistant "not knowing" things that are
 * in fact on the device.
 */
object KnowledgeIndexer {

    /** Passages longer than this are split further, on sentence boundaries. */
    const val MAX_CHARS = 900

    /** Passages shorter than this are merged into the next one. */
    const val MIN_CHARS = 120

    private val HEADING = Regex("""^(#{1,6})\s+(.*\S)\s*$""")

    /**
     * Chunks a Markdown or plain-text document. [sourceId] must be stable across
     * re-imports so a re-scan replaces passages instead of duplicating them.
     */
    fun chunk(sourceId: String, sourceTitle: String, content: String): List<KnowledgeChunk> {
        val out = ArrayList<KnowledgeChunk>()
        var section = ""
        val buffer = StringBuilder()

        fun flush() {
            val body = buffer.toString().trim()
            buffer.setLength(0)
            if (body.isEmpty()) return
            for (part in split(body)) {
                out += KnowledgeChunk(sourceId, sourceTitle, section, part)
            }
        }

        for (raw in content.lineSequence()) {
            val heading = HEADING.find(raw.trim())
            if (heading != null) {
                flush()
                section = heading.groupValues[2]
                continue
            }
            buffer.append(raw).append('\n')
            if (buffer.length >= MAX_CHARS * 2) flush()
        }
        flush()

        return merge(out)
    }

    /** Splits an over-long block on sentence ends, never mid-word. */
    private fun split(body: String): List<String> {
        if (body.length <= MAX_CHARS) return listOf(body)
        val parts = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in body.split(Regex("""(?<=[.!?])\s+"""))) {
            if (current.isNotEmpty() && current.length + sentence.length > MAX_CHARS) {
                parts += current.toString().trim()
                current.setLength(0)
            }
            if (sentence.length > MAX_CHARS) {
                // A single sentence longer than the budget: cut on whitespace.
                var rest = sentence
                while (rest.length > MAX_CHARS) {
                    val cut = rest.lastIndexOf(' ', MAX_CHARS).takeIf { it > 0 } ?: MAX_CHARS
                    parts += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                current.append(rest).append(' ')
            } else {
                current.append(sentence).append(' ')
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts.filter { it.isNotBlank() }
    }

    /** Folds tiny fragments into their neighbour so a passage carries context. */
    private fun merge(chunks: List<KnowledgeChunk>): List<KnowledgeChunk> {
        val out = ArrayList<KnowledgeChunk>()
        for (c in chunks) {
            val prev = out.lastOrNull()
            if (prev != null &&
                prev.section == c.section &&
                prev.text.length < MIN_CHARS &&
                prev.text.length + c.text.length <= MAX_CHARS
            ) {
                out[out.lastIndex] = prev.copy(text = prev.text + "\n" + c.text)
            } else {
                out += c
            }
        }
        return out
    }
}
