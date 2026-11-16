package com.simone.jarvismobile.core.quality

/** One assembled-context event. Counts, flags and names of reasons only: never any text of the user, of a document or of a memory. */
data class ContextEvent(
    val at: Long,
    val slot: String,
    val windowTokens: Int,
    val estimatedTokens: Int,
    /**
     * True only when [measuredTokens] holds a figure the RUNTIME reported for this request (LiteRT-LM `Conversation.getTokenCount()`, read after the turn).
     * It is never a pre-tokenisation of the prompt: [estimatedTokens] stays the figure the context budget plans with, and the two are not mixed.
     */
    val measured: Boolean,
    val droppedItems: Int,
    val reset: String,
    val generations: Int,
    val outcome: String,
    /** The runtime's own post-turn conversation token count (semantics undocumented), or null when none was obtained. */
    val measuredTokens: Int? = null,
    /** `maxNumTokens` the runtime was configured with for that slot; null when unknown. */
    val configuredWindow: Int? = null,
)

/** Bounded, in-memory, local: nothing leaves the device unless the user exports it. */
class ContextDiagnostics(private val capacity: Int = 50) {
    private val events = ArrayDeque<ContextEvent>()

    @Synchronized fun record(e: ContextEvent) {
        events.addLast(e)
        while (events.size > capacity) events.removeFirst()
    }

    @Synchronized fun snapshot(): List<ContextEvent> = events.toList()

    @Synchronized fun clear() = events.clear()

    /** Plain text for a voluntary export. [build] and [model] are identifiers of the app and the loaded model, supplied by the caller. */
    fun export(build: String, model: String): String = buildString {
        append("JARVIS context diagnostics\nbuild=").append(build).append("\nmodel=").append(model).append("\n")
        append("token figures are ESTIMATES unless measured=true; no conversation, document or memory text is included\n")
        snapshot().forEach { e ->
            append("t=").append(e.at).append(" slot=").append(e.slot).append(" window=").append(e.windowTokens)
                .append(" tokens=").append(e.estimatedTokens).append(if (e.measured) "(misurati)" else "(stima)")
                .append(" dropped=").append(e.droppedItems).append(" reset=").append(e.reset)
                .append(" generations=").append(e.generations).append(" outcome=").append(e.outcome).append('\n')
        }
    }
}
