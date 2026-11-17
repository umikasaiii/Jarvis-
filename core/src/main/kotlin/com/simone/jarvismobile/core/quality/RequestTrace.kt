package com.simone.jarvismobile.core.quality

/**
 * One line per chat request, answering "what did the pipeline do?" after the fact — WITHOUT keeping what the user said or what was answered.
 * It holds enums, counts, booleans and a bounded reason CODE; there is deliberately no free-text field, so notes, prompts, replies, documents,
 * passwords and personal data cannot reach it by construction. Local, bounded, clearable.
 */
data class RequestTrace(
    val atMs: Long,
    /** RequestKind name (FACTUAL, CONVERSATION, …). */
    val intent: String,
    val memoryQueried: Boolean,
    val memoryFound: Int,
    val memoryAdmitted: Int,
    val knowledgeQueried: Boolean,
    val candidatePassages: Int,
    /** "none" / "related" / "supported" — the evidence assembler's verdict on the retrieved passages. */
    val relevance: String,
    val retrievalError: Boolean,
    val modelAvailable: Boolean,
    /** Publication outcome code (verified, allowed_parts, faithful_extract, abstain, unverified:no_result, tool:…, …). */
    val publication: String,
    /** Routing decisions that changed the normal path: personal_first, general_only, slot_change, rotation, … */
    val fallbacks: List<String>,
    val durationMs: Long,
    /** Why it abstained / why the model was unavailable, as a code (never free text). */
    val reason: String,
) {
    fun render(): String = buildString {
        append(atMs).append(" intent=").append(sanitise(intent))
        append(" mem=").append(if (memoryQueried) "$memoryFound/$memoryAdmitted" else "off")
        append(" know=").append(if (knowledgeQueried) "$candidatePassages:${sanitise(relevance)}" else "off")
        if (retrievalError) append(" retrieval_error")
        append(" model=").append(if (modelAvailable) "ok" else "unavailable")
        append(" pub=").append(sanitise(publication))
        if (fallbacks.isNotEmpty()) append(" fb=").append(fallbacks.joinToString("+") { sanitise(it) })
        append(" ms=").append(durationMs)
        if (reason.isNotEmpty()) append(" why=").append(sanitise(reason))
    }

    companion object {
        /** Codes only: lower-case letters, digits and `_ : . -`, at most 40 characters. */
        fun sanitise(s: String): String = s.lowercase().map { if (it.isLetterOrDigit() || it in "_:.-") it else '_' }.joinToString("").take(40)
    }
}

/** Bounded ring of [RequestTrace]s with an optional sink (the app persists them to its own file). [clear] drops everything. */
class RequestTraceLog(private val capacity: Int = 100, private val sink: ((String) -> Unit)? = null) {
    private val ring = ArrayDeque<RequestTrace>()

    @Synchronized fun record(t: RequestTrace) {
        ring.addLast(t); while (ring.size > capacity) ring.removeFirst()
        sink?.invoke(t.render())
    }

    @Synchronized fun snapshot(): List<RequestTrace> = ring.toList()
    @Synchronized fun clear() = ring.clear()
    @Synchronized fun render(limit: Int = 20): String = ring.toList().takeLast(limit).joinToString("\n") { it.render() }.ifEmpty { "(nessuna richiesta registrata)" }
}
