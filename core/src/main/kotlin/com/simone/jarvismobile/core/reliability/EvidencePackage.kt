package com.simone.jarvismobile.core.reliability

import java.security.MessageDigest

/** One immutable piece of evidence: the EXACT text both the generator and the verifier see. */
data class EvidenceItem(
    /** Stable within a package ("E1", "E2"…); the only reference the renderer ever prints. */
    val id: String,
    val title: String,
    val section: String,
    val text: String,
    /** Opaque key the caller uses to map an item back to its source (chunk index, block index…). */
    val sourceKey: String,
)

/**
 * RELIABILITY — the immutable evidence handed to the model AND to the claim verifier. There is exactly one rendering
 * ([render]) and one text per item, cut at a sentence boundary when the package is built, so the prompt can never contain a
 * shorter (or longer) passage than the one the verifier reads. Citations are produced by the caller from [EvidenceItem.sourceKey]
 * of the items that actually supported the published text, never from model output; [sanitizeReferences] removes any marker
 * the model invented. Retrieved text is DATA: the rendering says so and neutralises role/template markers.
 */
class EvidencePackage private constructor(val items: List<EvidenceItem>) {
    /** Changes whenever an id, a title or a character of any item changes. */
    val fingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest(items.joinToString("\u0001") { "${it.id}\u0002${it.title}\u0002${it.section}\u0002${it.text}" }.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    val isEmpty: Boolean get() = items.isEmpty()

    fun item(id: String): EvidenceItem? = items.firstOrNull { it.id == id }

    /** The block pasted into the prompt. Item ids are `[E1]`-style; the verifier is given the very same items. */
    fun render(): String = items.joinToString("\n\n") { i ->
        buildString {
            append('[').append(i.id).append("] ").append(i.title)
            if (i.section.isNotBlank()) append(" — ").append(i.section)
            append('\n').append(i.text)
        }
    }

    /** Removes citation markers that do not name an item of this package ("[7]" with 3 items, "[E9]"). Valid ones are kept. */
    fun sanitizeReferences(text: String): String {
        val valid = items.map { it.id }.toSet()
        return MARKER.replace(text) { m ->
            val raw = m.groupValues[1]
            val id = if (raw.all { it.isDigit() }) "E$raw" else raw.uppercase()
            if (id in valid) m.value else ""
        }.replace(Regex("[ \\t]{2,}"), " ").replace(" .", ".").replace(" ,", ",")
    }

    companion object {
        private val MARKER = Regex("\\[\\s*(E?\\d{1,3})\\s*]", RegexOption.IGNORE_CASE)
        private val ROLE = com.simone.jarvismobile.core.regex.PortableRegex.of("(?imU)^\\s*(?:utente|user|assistente|assistant|system|sistema|human)\\s*:")
        private val TEMPLATE = Regex("<\\|[^>]*\\||\\[/?INST]|<</?SYS>>|<start_of_turn>|<end_of_turn>|```")

        fun empty() = EvidencePackage(emptyList())

        /** Builds ids E1… in order; every text is neutralised and cut at a sentence boundary within [maxCharsPerItem]. */
        fun of(raw: List<Raw>, maxCharsPerItem: Int = 900): EvidencePackage = EvidencePackage(
            raw.filter { it.text.isNotBlank() }.mapIndexed { i, r ->
                EvidenceItem("E${i + 1}", r.title.trim(), r.section.trim(), cutAtSentence(neutralise(r.text), maxCharsPerItem), r.sourceKey)
            },
        )

        data class Raw(val title: String, val section: String, val text: String, val sourceKey: String)

        fun neutralise(t: String): String = TEMPLATE.replace(ROLE.replace(t.trim(), ""), "").replace(Regex("[ \\t]+"), " ")

        /** The longest prefix ≤ [max] that ends on a sentence end; if none exists, a word boundary. Never mid-word, never mid-number. */
        fun cutAtSentence(text: String, max: Int): String {
            if (text.length <= max) return text
            val head = text.substring(0, max)
            var cut = -1
            var i = head.length - 1
            while (i > 0) {
                val c = head[i]
                if ((c == '.' || c == '!' || c == '?' || c == '\n') && (i + 1 >= head.length || head[i + 1].isWhitespace()) &&
                    !(c == '.' && i > 0 && head[i - 1].isDigit() && i + 1 < head.length && head[i + 1].isDigit())
                ) { cut = i + 1; break }
                i--
            }
            if (cut > max / 3) return head.substring(0, cut).trim()
            val sp = head.lastIndexOf(' ')
            return (if (sp > 0) head.substring(0, sp) else head).trim()
        }
    }
}
