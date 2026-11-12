package com.simone.jarvismobile.core.llm

import java.util.ArrayDeque

enum class InferenceEventType {
    LOAD_START, LOAD_OK, LOAD_FAIL, UNLOAD,
    CHAT_START, CHAT_END, ONCE_START, ONCE_END,
    CONV_OPENED, CONV_DISCARDED,
    RESET_APPLIED, RESET_DEFERRED,
    CANCEL_REQUESTED, TIMEOUT, BUSY, WEDGED, FAILED,
    DEGENERATE, DEGENERATE_RETRY,
    APP_FOREGROUND, APP_BACKGROUND, SCREEN_OFF, SCREEN_ON, USER_PRESENT,
    PROCESS_START, PREVIOUS_EXIT, MEMORY,
    TURN_BEGIN, TURN_COMMIT, TURN_DISCARD, TURN_STOP, TURN_NEW_CONVERSATION,
}

/**
 * One diagnostic fact. DELIBERATELY has no free-text field for content: only a type, a slot label, numbers
 * and a sanitised identifier-like [tag] (see [sanitiseTag]). Conversation text, prompts, replies and personal
 * data can never reach this log by construction.
 */
data class InferenceEvent(
    val atMs: Long,
    val type: InferenceEventType,
    val slot: String,
    val epoch: Long = -1,
    val durationMs: Long = -1,
    val n1: Long = -1,
    val n2: Long = -1,
    val tag: String = "",
) {
    fun render(): String = buildString {
        append(atMs).append(' ').append(type.name).append(" slot=").append(slot)
        if (epoch >= 0) append(" epoch=").append(epoch)
        if (durationMs >= 0) append(" ms=").append(durationMs)
        if (n1 >= 0) append(" n1=").append(n1)
        if (n2 >= 0) append(" n2=").append(n2)
        if (tag.isNotEmpty()) append(" tag=").append(tag)
    }
}

/** Bounded in-memory ring of [InferenceEvent]s with an optional sink (the app persists them to a file). */
class InferenceEventLog(
    private val capacity: Int = 400,
    private val sink: ((InferenceEvent) -> Unit)? = null,
) {
    private val ring = ArrayDeque<InferenceEvent>()

    fun record(event: InferenceEvent) {
        synchronized(ring) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(event)
        }
        runCatching { sink?.invoke(event) }
    }

    fun snapshot(): List<InferenceEvent> = synchronized(ring) { ring.toList() }

    fun render(limit: Int = 100): String = snapshot().takeLast(limit).joinToString("\n") { it.render() }

    companion object {
        /** Keeps only `[A-Za-z0-9_]`, max 48 chars: a tag can name a reason, never carry text. */
        fun sanitiseTag(raw: String): String = raw.filter { it.isLetterOrDigit() || it == '_' }.take(48)
    }
}
