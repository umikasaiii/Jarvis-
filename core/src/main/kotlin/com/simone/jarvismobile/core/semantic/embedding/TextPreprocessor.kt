package com.simone.jarvismobile.core.semantic.embedding

import java.text.Normalizer

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 13 §G/§P. The ONE
 * canonical preprocessing step BEFORE tokenization — implements exactly
 * [SemanticEncoderContract.CURRENT]'s `unicodeNormalizationForm`/
 * `collapseWhitespace`/`trimText`/`casingPolicy` fields, nothing more.
 *
 * This is real, genuinely testable parity: [java.text.Normalizer] (JDK
 * standard library, available in this pure-`:core` module — no Android
 * dependency) implements the same Unicode Standard Annex #15 NFC algorithm
 * that Python's `unicodedata.normalize("NFC", text)` implements — both are
 * standard-conformant implementations of the SAME published algorithm, not
 * two independently-guessed behaviors, so exact cross-language parity here
 * is a well-founded claim (§Q) — unlike tokenization itself, which this
 * pass explicitly does NOT claim parity for (no real tokenizer exists to
 * be parity-tested against, see `SemanticTokenizer.kt`'s honesty note).
 *
 * `tools/semantic_classifier/preprocessing.py` is the Python mirror of
 * exactly this function — same three steps, same order.
 */
object TextPreprocessor {
    /**
     * Canonicalizes [text] per [SemanticEncoderContract.CURRENT]:
     * 1. NFC Unicode normalization.
     * 2. Trim leading/trailing whitespace.
     * 3. Collapse any run of Unicode whitespace to a single ASCII space.
     * Casing is deliberately never altered (`casingPolicy = "PRESERVE"`).
     */
    fun canonicalize(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val trimmed = normalized.trim()
        return trimmed.replace(Regex("\\s+"), " ")
    }
}
