package com.simone.jarvismobile.core.quality

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Why a generation ended, as far as the runtime lets the app know. */
enum class Termination { COMPLETED, CANCELLED, TIMEOUT, ERROR, NO_OUTPUT }

/**
 * HOTFIX 5 — per-generation METADATA only: sizes, counts, enums and timings. There is no text field in this
 * type, so a prompt, a question or a reply cannot be persisted through it by construction. Values the runtime
 * does not expose (token counts, finish reason) are NOT measured and are rendered as `UNAVAILABLE`.
 */
data class GenerationTrace(
    val atMs: Long,
    /** Sequence of the turn in [com.simone.jarvismobile.core.chat.TurnLedger], or -1 for a non-ledger turn (voice). */
    val turnSeq: Long,
    val engineMode: String,        // "classic" | "conversational"
    val path: String,              // chat_general | chat_grounded | chat_tool_grounded
    val slot: String,              // fast | advanced
    val conversationEpoch: Long,
    val systemChars: Int,
    val notesChars: Int,
    val historyChars: Int,
    val evidenceChars: Int,
    val userChars: Int,
    val estPromptTokens: Int,      // ESTIMATE (3 chars/token), see TokenEstimator
    val windowTokens: Int,
    val replyReserveTokens: Int,
    val seedTurns: Int,
    val droppedHistoryTurns: Int,
    val droppedNotes: Int,
    val rotated: Boolean,
    val generationMs: Long,
    val rawChars: Int,
    val cleanChars: Int,
    val flags: String,             // comma separated AnswerQuality.Flag names or "-"
    val retried: Boolean,
    val termination: Termination,
    val presented: String,         // provenance applied: TOOL | SOURCE | MODEL_GENERAL
    val noteAppended: Boolean,
) {
    fun render(): String = buildString {
        append("GEN ").append(atMs).append(" turn=").append(turnSeq).append(" mode=").append(engineMode)
        append(" path=").append(path).append(" slot=").append(slot).append(" epoch=").append(conversationEpoch)
        append(" chars[sys=").append(systemChars).append(" notes=").append(notesChars).append(" hist=").append(historyChars)
        append(" evid=").append(evidenceChars).append(" user=").append(userChars).append(']')
        append(" estTokens=").append(estPromptTokens).append("(estimate) window=").append(windowTokens)
        append(" reserve=").append(replyReserveTokens)
        append(" seedTurns=").append(seedTurns).append(" droppedHist=").append(droppedHistoryTurns)
        append(" droppedNotes=").append(droppedNotes).append(" rotated=").append(rotated)
        append(" genMs=").append(generationMs).append(" rawChars=").append(rawChars).append(" cleanChars=").append(cleanChars)
        append(" flags=").append(flags).append(" retried=").append(retried).append(" end=").append(termination.name)
        append(" provenance=").append(presented).append(" note=").append(noteAppended)
        append(" outputTokens=UNAVAILABLE finishReason=UNAVAILABLE")
    }
}

/** Bounded ring of traces with an optional persistence sink (metadata lines only). */
class GenerationTraceLog(private val capacity: Int = 100, private val sink: ((String) -> Unit)? = null) {
    private val ring = ArrayDeque<GenerationTrace>()

    fun record(t: GenerationTrace) {
        synchronized(ring) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(t)
        }
        runCatching { sink?.invoke(t.render()) }
    }

    fun snapshot(): List<GenerationTrace> = synchronized(ring) { ring.toList() }

    fun render(limit: Int = 20): String = snapshot().takeLast(limit).joinToString("\n") { it.render() }
}

/**
 * HOTFIX 5 — TEMPORARY, EXPLICIT, LOCAL text inspection of the last few generations. Off by default; when the user
 * switches it on it expires by itself and holds the text IN MEMORY only (never written to disk, never sent
 * anywhere, gone when the process dies). The persistent trace never receives this text.
 */
object DebugTextProbe {
    data class Capture(val turnSeq: Long, val system: String, val userMessage: String, val raw: String, val cleaned: String)

    private val enabledUntilMs = AtomicLong(0L)
    private val ring = ArrayDeque<Capture>()
    const val MAX_ENTRIES = 5
    const val DEFAULT_WINDOW_MS = 10 * 60_000L

    fun enable(nowMs: Long, windowMs: Long = DEFAULT_WINDOW_MS) { enabledUntilMs.set(nowMs + windowMs) }

    fun disable() { enabledUntilMs.set(0L); clear() }

    fun isEnabled(nowMs: Long): Boolean = nowMs < enabledUntilMs.get()

    fun capture(nowMs: Long, c: Capture) {
        if (!isEnabled(nowMs)) return
        synchronized(ring) {
            if (ring.size >= MAX_ENTRIES) ring.removeFirst()
            ring.addLast(c)
        }
    }

    fun snapshot(nowMs: Long): List<Capture> = if (isEnabled(nowMs)) synchronized(ring) { ring.toList() } else { clear(); emptyList() }

    fun clear() { synchronized(ring) { ring.clear() } }
}
