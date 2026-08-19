package com.simone.jarvismobile.core.engine

import com.simone.jarvismobile.core.protocol.AssistantResponse
import com.simone.jarvismobile.core.speech.SpeechShaper

/**
 * What `JarvisBrain.reply()` emits as a `Flow<BrainEvent>`.
 *
 * There is no real token-level streaming API in the LLM stack today (see
 * `EngineTurnDiagnostics.timeToFirstEmitMs`'s doc comment) — [Sentence] events
 * are produced by chunking an already-complete reply with the existing
 * [SpeechShaper], the same sentence splitter final TTS output already goes
 * through. This is real, working incremental UI/TTS delivery, just not
 * reduced generation latency. The shape is chosen so that if the LLM layer
 * ever gains a genuine per-token callback, only the producer changes — every
 * collector of this `Flow` is already correct for real streaming.
 */
sealed interface BrainEvent {
    /** One sentence-sized chunk of the reply, ready to show/speak incrementally. */
    data class Sentence(val text: String) : BrainEvent

    /** The full, final structured response — always the last event emitted. */
    data class Done(val response: AssistantResponse) : BrainEvent
}

/** Turns a completed [AssistantResponse] into the [BrainEvent] sequence above. */
object SentenceStream {
    fun from(response: AssistantResponse): List<BrainEvent> {
        val sentences = SpeechShaper.shape(response.assistantText)
            .map { it.text }
            .filter { it.isNotBlank() }
        val events = ArrayList<BrainEvent>(sentences.size + 1)
        sentences.forEach { events += BrainEvent.Sentence(it) }
        events += BrainEvent.Done(response)
        return events
    }
}
