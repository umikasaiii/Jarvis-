package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.protocol.AssistantResponse

/**
 * What `JarvisBrain.reply()` returns for one model turn — a closed result
 * instead of a nullable [AssistantResponse], so "the model produced valid
 * structured output" and "the model is unavailable" are never conflated (the
 * one case `ConversationalJarvisEngine` treats as a Classic-engine fallback
 * signal, at the `JarvisEngineRouter` layer) and "output that needed the
 * one-repair-attempt fallback to plain text" is visible for diagnostics
 * ([EngineTurnDiagnostics.parseError]) without a second, stateful call.
 */
sealed interface BrainReply {
    /**
     * A response was produced. [parsedCleanly] is false when
     * `ResponseParser` had to fall back to plain text (see its own
     * documented contract: one repair attempt, then plain text, never a
     * tool call on invalid JSON) — still a usable reply, just worth
     * recording for diagnostics.
     */
    data class Ready(val response: AssistantResponse, val parsedCleanly: Boolean) : BrainReply

    /** The local model itself is not loaded/available. */
    data object Unavailable : BrainReply
}
