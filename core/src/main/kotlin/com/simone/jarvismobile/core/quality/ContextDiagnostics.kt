package com.simone.jarvismobile.core.quality

/** One assembled-context event. Counts, flags and names of reasons only: never any text of the user, of a document or of a memory. */
data class ContextEvent(
    val at: Long,
    val slot: String,
    val windowTokens: Int,
    val estimatedTokens: Int,
    /** True only if the figure came from a real tokenizer; with LiteRT-LM 0.14.0 this is always false. */
    val measured: Boolean,
    val droppedItems: Int,
    val reset: String,
    val generations: Int,
    val outcome: String,
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
