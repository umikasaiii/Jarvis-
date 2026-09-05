package com.simone.jarvismobile.core.tools

/**
 * § FASE 2A.8 RELEASE GATE A/C — "Che differenza c'è tra RAM e VRAM?" (a
 * KNOWLEDGE question, answered by the model, never a tool) followed by
 * "Quanta ne ho nel telefono?" (a genuine DATA_QUERY, but with no metric noun
 * of its own — "ne" is a bare Italian partitive pronoun referring back to
 * whatever was just discussed) is the exact anaphora case audited: resolving
 * it needs (1) noticing which device-metric noun a knowledge exchange
 * mentioned, (2) recognizing the bare "quanta ne ho" follow-up shape, and
 * (3) knowing which of those nouns [GetDeviceInfoTool][com.simone.jarvismobile.tools.GetDeviceInfoTool]
 * can genuinely answer — never guessing a number for one it cannot (VRAM has
 * no reliable value on stock Android: it is unified memory, not a separate
 * pool). Pure, deterministic, no second LLM.
 */
object DeviceInfoFollowUp {

    private val topicPatterns: List<Pair<String, Regex>> = listOf(
        "ram" to Regex("""\bram\b""", RegexOption.IGNORE_CASE),
        "vram" to Regex("""\bvram\b""", RegexOption.IGNORE_CASE),
        "rom" to Regex("""\brom\b""", RegexOption.IGNORE_CASE),
        "storage" to Regex("""\b(storage|memoria interna|spazio di archiviazione)\b""", RegexOption.IGNORE_CASE),
        "android_version" to Regex("""\bversione\s+(di\s+)?android\b""", RegexOption.IGNORE_CASE),
        "device_model" to Regex("""\bmodello\s+(del\s+)?(telefono|dispositivo)\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Which device-metric noun(s) [text] mentions, for remembering as "what
     * the last knowledge exchange was about" — never itself a trigger to run
     * a tool (a plain "Che differenza c'è tra RAM e VRAM?" stays a KNOWLEDGE
     * question, answered by the model). When several are mentioned, "ram" is
     * preferred: it is the one metric a follow-up about it can actually be
     * answered for (see [resolveDeviceInfoMetric]), so it is the more useful
     * thing to remember than an unanswerable one.
     */
    fun extractTopic(text: String): String? {
        val matched = topicPatterns.filter { (_, re) -> re.containsMatchIn(text) }.map { it.first }
        return matched.firstOrNull { it == "ram" } ?: matched.firstOrNull()
    }

    /**
     * The [GetDeviceInfoTool][com.simone.jarvismobile.tools.GetDeviceInfoTool]
     * metric code for [topic], or `null` when Android exposes no reliable
     * value for it — "vram" specifically: mobile Android has no dedicated
     * VRAM pool distinct from RAM, so answering it as if it were RAM would be
     * a silent, wrong substitution, never done here.
     */
    fun resolveDeviceInfoMetric(topic: String): String? = when (topic) {
        "ram" -> "ram"
        "storage", "rom" -> "storage"
        "android_version" -> "android_version"
        "device_model" -> "device_model"
        else -> null
    }

    private val partitiveFollowUpPattern = Regex("""\bquant[oa]\s+ne\s+ho\b""", RegexOption.IGNORE_CASE)

    /** "Quanta ne ho (nel telefono)?" — a bare partitive quantity question with no metric noun of its own; only resolvable with a remembered topic. */
    fun looksLikePartitiveFollowUp(text: String): Boolean = partitiveFollowUpPattern.containsMatchIn(text)
}
