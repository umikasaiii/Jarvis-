package com.simone.jarvismobile.core.translate

/**
 * Decides *when* a stretch of speech is a complete enough segment to translate.
 *
 * Translating token by token would be fast but wrong — "voglio", "andare",
 * "alla" arrive as fragments and mistranslate. This waits for a real unit: a
 * final transcript, a sentence-ending punctuation, or a short quiet gap after
 * the last word. It emits the newly-completed tail only, so a growing partial
 * doesn't re-translate what it already sent.
 *
 * Pure and time-injected so the debounce is unit-tested, not judged by ear.
 */
class TranslationSegmenter(
    private val minChars: Int = 2,
    private val quietMs: Long = 600L,
) {
    private var text: String = ""
    private var lastChangeMs: Long = 0L
    private var emittedLen: Int = 0

    /**
     * Feeds a transcript update (partial or final) and returns any segments that
     * just became complete, in order. Usually empty or one; a long final can
     * yield several sentences.
     */
    fun offer(transcript: String, isFinal: Boolean, nowMs: Long): List<String> {
        val t = transcript.trim()
        if (t != text) {
            text = t
            lastChangeMs = nowMs
        }
        if (emittedLen > text.length) emittedLen = 0 // the partial was revised shorter

        val out = ArrayList<String>()

        // Emit each completed sentence past what we've already sent.
        var i = emittedLen
        while (i < text.length) {
            if (text[i] in SENTENCE_ENDERS) {
                val seg = text.substring(emittedLen, i + 1).trim()
                if (wordy(seg)) {
                    out += seg
                    emittedLen = i + 1
                }
            }
            i++
        }

        if (isFinal) {
            val tail = text.substring(emittedLen.coerceAtMost(text.length)).trim()
            if (wordy(tail)) out += tail
            reset()
            return out
        }

        // A quiet gap after the last word closes the tail even without punctuation.
        if (out.isEmpty() && nowMs - lastChangeMs >= quietMs) {
            val tail = text.substring(emittedLen).trim()
            if (wordy(tail)) {
                out += tail
                emittedLen = text.length
            }
        }
        return out
    }

    fun reset() {
        text = ""
        lastChangeMs = 0L
        emittedLen = 0
    }

    /** Enough of a unit to translate: a sentence, two words, or some CJK. */
    private fun wordy(s: String): Boolean {
        val t = s.trim()
        if (t.length < minChars) return false
        if (endsSentence(t)) return true
        val words = t.split(WS).count { it.isNotBlank() }
        if (words >= 2) return true
        val cjk = t.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
        return cjk && t.length >= 3
    }

    private fun endsSentence(s: String): Boolean =
        s.trimEnd().lastOrNull()?.let { it in SENTENCE_ENDERS } ?: false

    private companion object {
        val SENTENCE_ENDERS = ".!?;…。！？；".toCharArray().toSet()
        val WS = Regex("\\s+")
    }
}
