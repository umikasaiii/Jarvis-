package com.simone.jarvismobile.core.reliability

import com.simone.jarvismobile.core.regex.PortableRegex
import java.security.MessageDigest

enum class EvidenceKind { KNOWLEDGE, DOCUMENT, MEMORY, TOOL }

/** One immutable piece of evidence: the EXACT text both the generator and the verifier see. */
data class EvidenceItem(
    /** Stable within a package ("E1", "E2"…). */
    val id: String,
    val title: String,
    val section: String,
    val text: String,
    /** Opaque key the caller uses to map an item back to its source (chunk index, block index…). */
    val sourceKey: String,
    val kind: EvidenceKind = EvidenceKind.KNOWLEDGE,
    /** Where [text] sits in the sanitised source text it was cut from. */
    val rangeStart: Int = 0,
    val rangeEnd: Int = text.length,
)

/** A source that did NOT make it into the package, and why (never silently dropped). */
data class OmittedEvidence(val title: String, val sourceKey: String, val reason: String)

/**
 * RELIABILITY — the immutable evidence handed to the model AND to the claim verifier, built ONCE before inference, after
 * sanitisation and after every size limit. There is exactly one rendering ([render]) and one text per item, so the prompt can
 * never contain a shorter (or longer) passage than the one the verifier reads: text left out of the prompt cannot support an answer.
 * An item is cut only at a sentence boundary; a first sentence that does not fit is not cut into a fake complete one — the item is
 * [omitted] with its reason, so a truncated negation/condition can never become "proof". Citations are rebuilt by the policy from
 * verified links, never copied from model output. Retrieved text is DATA: role/template markers are neutralised.
 */
class EvidencePackage private constructor(val items: List<EvidenceItem>, val omitted: List<OmittedEvidence>) {
    /** Changes whenever an id, a title, a range or a character of any item changes. */
    val fingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest(items.joinToString("\u0001") { "${it.id}\u0002${it.kind}\u0002${it.title}\u0002${it.section}\u0002${it.rangeStart}-${it.rangeEnd}\u0002${it.text}" }.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    val isEmpty: Boolean get() = items.isEmpty()

    fun item(id: String): EvidenceItem? = items.firstOrNull { it.id == id }

    /** The block pasted into the prompt. The verifier is given the very same items. */
    fun render(): String = items.joinToString("\n\n") { i ->
        buildString {
            append('[').append(i.id).append("] ").append(i.title)
            if (i.section.isNotBlank()) append(" — ").append(i.section)
            append('\n').append(i.text)
        }
    } + (if (omitted.isEmpty()) "" else "\n\n(Altri ${omitted.size} passaggi non inclusi per spazio: non usarli.)")

    /** The rendering of only some kinds of evidence (the prompt shows documents, knowledge and tool results in separate blocks). */
    fun render(kinds: Set<EvidenceKind>): String = items.filter { it.kind in kinds }.joinToString("\n\n") { i ->
        buildString {
            append('[').append(i.id).append("] ").append(i.title)
            if (i.section.isNotBlank()) append(" — ").append(i.section)
            append('\n').append(i.text)
        }
    }

    fun has(kind: EvidenceKind): Boolean = items.any { it.kind == kind }

    /** Same package, plus [more] items appended (ids continue); used to add tool results to knowledge evidence BEFORE generation. */
    fun plus(more: EvidencePackage): EvidencePackage {
        var n = items.size
        return EvidencePackage(items + more.items.map { it.copy(id = "E${++n}") }, omitted + more.omitted)
    }

    companion object {
        private val ROLE = PortableRegex.of("(?imU)^\\s*(?:utente|user|assistente|assistant|system|sistema|human)\\s*:")
        private val TEMPLATE = Regex("<\\|[^>]*\\||\\[/?INST]|<</?SYS>>|<start_of_turn>|<end_of_turn>|```")

        fun empty() = EvidencePackage(emptyList(), emptyList())

        data class Raw(val title: String, val section: String, val text: String, val sourceKey: String, val kind: EvidenceKind = EvidenceKind.KNOWLEDGE)

        /**
         * Builds ids E1… in order. Every text is neutralised, then cut at a sentence boundary within [maxCharsPerItem]; the total
         * is bounded by [totalChars] (later sources are omitted explicitly, in order).
         */
        fun of(raw: List<Raw>, maxCharsPerItem: Int = 900, totalChars: Int = Int.MAX_VALUE): EvidencePackage {
            val items = ArrayList<EvidenceItem>()
            val omitted = ArrayList<OmittedEvidence>()
            var used = 0
            for (r in raw) {
                val clean = neutralise(r.text)
                if (clean.isBlank()) continue
                val cut = cutRange(clean, maxCharsPerItem)
                if (cut == null) { omitted += OmittedEvidence(r.title, r.sourceKey, "prima frase più lunga del limite"); continue }
                val text = clean.substring(cut.first, cut.last + 1).trim()
                if (used + text.length > totalChars) { omitted += OmittedEvidence(r.title, r.sourceKey, "spazio esaurito"); continue }
                used += text.length
                items += EvidenceItem("E${items.size + 1}", r.title.trim(), r.section.trim(), text, r.sourceKey, r.kind, cut.first, cut.last + 1)
            }
            return EvidencePackage(items, omitted)
        }

        fun neutralise(t: String): String = TEMPLATE.replace(ROLE.replace(t.trim(), ""), "").replace(Regex("[ \\t]+"), " ")

        /** The longest prefix of whole sentences that fits [max]; null when not even the first sentence fits. */
        fun cutRange(text: String, max: Int): IntRange? {
            if (text.length <= max) return 0 until text.length
            var end = -1
            for (e in Sentences.ends(text)) { if (e <= max) end = e else break }
            return if (end <= 0) null else 0 until end
        }

        fun cutAtSentence(text: String, max: Int): String = cutRange(text, max)?.let { text.substring(it.first, it.last + 1).trim() } ?: ""
    }
}
