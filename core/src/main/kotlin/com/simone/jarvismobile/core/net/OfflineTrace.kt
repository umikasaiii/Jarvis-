package com.simone.jarvismobile.core.net

/**
 * M3-E — a bounded, content-free local trace of offline decisions and runtime lifecycle events. It stores NO prompt, message, document text, memory,
 * credential, token, URL, host or IP: only technical ids, the operation label, mode, decision, cause and category/duration. Nothing leaves the device;
 * export is a function the USER invokes after seeing the preview.
 */
data class TraceEvent(
    val at: Long,
    /** Technical request id (a UUID the app generated) or "-". */
    val requestId: String,
    val slot: String,
    /** "net:<operation>" or "llm:<event>". */
    val component: String,
    val mode: String,
    val decision: String,
    val cause: String,
    /** ok / failed / blocked / cancelled / timeout / … — a category, never a message. */
    val result: String,
    val durationMs: Long,
    /** Optional numeric facts (estimated vs measured tokens…), labelled by [numbers] keys. */
    val numbers: Map<String, Long> = emptyMap(),
)

class OfflineTrace(private val capacity: Int = 200, private val now: () -> Long = System::currentTimeMillis) {
    private val ring = ArrayDeque<TraceEvent>()
    @Synchronized fun add(e: TraceEvent) { if (ring.size >= capacity) ring.removeFirst(); ring.addLast(sanitise(e)) }
    @Synchronized fun events(): List<TraceEvent> = ring.toList()
    @Synchronized fun clear() = ring.clear()

    fun net(requestId: String, op: NetOperation, mode: OfflineMode, d: NetDecision, result: String, durationMs: Long = 0, slot: String = "-") =
        add(TraceEvent(now(), requestId, slot, "net:" + op.label, mode.name, d.decision.name, d.cause.name, result, durationMs))

    fun llm(requestId: String, slot: String, event: String, result: String, durationMs: Long = 0, numbers: Map<String, Long> = emptyMap()) =
        add(TraceEvent(now(), requestId, slot, "llm:$event", "-", "-", "-", result, durationMs, numbers))

    /** What the user sees BEFORE deciding to export: counts and the exact JSON that would be written. */
    data class Preview(val events: Int, val json: String, val containsFreeText: Boolean)

    @Synchronized fun preview(): Preview = Preview(ring.size, toJson(ring.toList()), containsFreeText = false)

    /** The user pressed "export": returns the same text the preview showed; nothing is sent anywhere by this class. */
    @Synchronized fun exportForUser(confirmed: Boolean): String? = if (confirmed) toJson(ring.toList()) else null

    companion object {
        private val URLISH = Regex("(?i)(https?://|www\\.|\\d{1,3}(\\.\\d{1,3}){3}|[/\\\\])")
        private val ID = Regex("[A-Za-z0-9\\-]{1,40}")

        /** CLOSED vocabularies: a free-text string cannot be stored because only these exact tokens are ever written (anything else becomes "other"). */
        val RESULTS = setOf("ok", "failed", "blocked", "cancelled", "timeout", "wedged", "oom", "missing_file", "unsupported_format", "unsupported_backend", "interrupted", "partial", "unknown", "-")
        val LLM_EVENTS = setOf("load", "unload", "reset", "cancel", "timeout", "generate", "wedged", "oom", "interrupted_load_marker", "residency_park", "tokens")
        val SLOTS = setOf("fast", "advanced", "classifier", "-")
        val NUMBER_KEYS = setOf("estimatedTokens", "measuredTokens", "configuredWindow", "plannedWindow", "pssBeforeMb", "pssAfterMb", "availMb", "totalMb", "attempts", "turn")

        private fun pick(v: String, allowed: Set<String>) = v.lowercase().takeIf { it in allowed } ?: "other"

        fun clean(s: String, max: Int = 48): String = if (URLISH.containsMatchIn(s)) "redacted" else s.filter { it.isLetterOrDigit() || it in "_:.-" }.take(max)

        fun sanitise(e: TraceEvent): TraceEvent {
            val comp = when {
                e.component.startsWith("net:") -> NetOperation.values().firstOrNull { "net:" + it.label == e.component }?.let { e.component } ?: "net:other"
                e.component.startsWith("llm:") -> "llm:" + pick(e.component.removePrefix("llm:"), LLM_EVENTS)
                else -> "other"
            }
            return e.copy(
                requestId = if (ID.matches(e.requestId)) e.requestId else "redacted", slot = pick(e.slot, SLOTS), component = comp,
                mode = OfflineMode.values().firstOrNull { it.name == e.mode }?.name ?: "-",
                decision = Decision.values().firstOrNull { it.name == e.decision }?.name ?: "-",
                cause = Cause.values().firstOrNull { it.name == e.cause }?.name ?: "-",
                result = pick(e.result, RESULTS),
                numbers = e.numbers.filterKeys { it in NUMBER_KEYS }.entries.take(8).associate { it.key to it.value },
            )
        }

        fun toJson(events: List<TraceEvent>): String = buildString {
            append("{\"format\":\"jarvis-offline-trace-v1\",\"events\":[")
            events.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("{\"at\":").append(e.at).append(",\"req\":\"").append(e.requestId).append("\",\"slot\":\"").append(e.slot)
                    .append("\",\"c\":\"").append(e.component).append("\",\"mode\":\"").append(e.mode).append("\",\"d\":\"").append(e.decision)
                    .append("\",\"cause\":\"").append(e.cause).append("\",\"r\":\"").append(e.result).append("\",\"ms\":").append(e.durationMs)
                    .append(",\"n\":{").append(e.numbers.entries.joinToString(",") { "\"${it.key}\":${it.value}" }).append("}}")
            }
            append("]}")
        }
    }
}
