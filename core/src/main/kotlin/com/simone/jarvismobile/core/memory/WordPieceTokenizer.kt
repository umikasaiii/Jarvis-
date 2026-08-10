package com.simone.jarvismobile.core.memory

/**
 * A BERT-style WordPiece tokenizer — the piece a sentence-embedding model needs
 * to turn text into the token ids it was trained on. Pure and dependency-free so
 * it is unit-tested off-device; the [vocab] (a `vocab.txt` loaded into a map) is
 * supplied at runtime alongside the ONNX model.
 *
 * Deliberately faithful to the reference algorithm: lowercase (for an uncased
 * model), split off punctuation, then greedy longest-match sub-wording with the
 * `##` continuation marker, wrapped in [CLS]/[SEP]. An out-of-vocab word becomes
 * a single [UNK] rather than a wrong split.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val lowercase: Boolean = true,
    private val maxTokens: Int = 128,
) {
    private val unk = vocab[UNK] ?: 0
    private val cls = vocab[CLS] ?: 0
    private val sep = vocab[SEP] ?: 0

    /** Token ids for [text], including the leading [CLS] and trailing [SEP]. */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        ids += cls
        for (word in basicTokenize(text)) {
            if (ids.size >= maxTokens - 1) break
            ids += wordPiece(word)
        }
        if (ids.size > maxTokens - 1) {
            while (ids.size > maxTokens - 1) ids.removeAt(ids.size - 1)
        }
        ids += sep
        return ids.toIntArray()
    }

    /** Whitespace + punctuation splitting, matching the reference basic tokenizer. */
    private fun basicTokenize(text: String): List<String> {
        val normalized = if (lowercase) text.lowercase() else text
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
        for (c in normalized) {
            when {
                c.isWhitespace() -> flush()
                isPunctuation(c) -> { flush(); out += c.toString() }
                else -> sb.append(c)
            }
        }
        flush()
        return out
    }

    /** Greedy longest-match sub-wording; the whole word becomes [UNK] if it can't be covered. */
    private fun wordPiece(word: String): List<Int> {
        if (word.length > MAX_WORD) return listOf(unk)
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[piece]
                if (id != null) { found = id; break }
                end--
            }
            if (found == null) return listOf(unk)
            pieces += found
            start = end
        }
        return pieces
    }

    private fun isPunctuation(c: Char): Boolean {
        val code = c.code
        // ASCII punctuation ranges plus any Unicode punctuation category.
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(c)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> true
            else -> false
        }
    }

    companion object {
        const val UNK = "[UNK]"
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        private const val MAX_WORD = 100
    }
}
