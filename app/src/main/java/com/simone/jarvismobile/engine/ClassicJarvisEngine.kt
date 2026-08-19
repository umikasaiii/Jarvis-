package com.simone.jarvismobile.engine

/**
 * Wraps today's Classic dispatch behaviour behind the shared [JarvisEngine]
 * contract, without moving or rewriting a single line of it.
 *
 * The real implementation stays exactly where it already lives —
 * `SessionCoordinator`'s existing turn-dispatch logic (deterministic command
 * matching, Modalità Pro, the translator/navigation/automation phrase
 * parsers, `TurnPlanner`, chat fallback) — because that logic reads and
 * mutates a large cluster of `SessionCoordinator`'s own private state
 * (`pendingConfirmation`, `pendingSlot`, `awaitingAnswer`,
 * `lastAgendaEntryId`, and more). Mechanically relocating that cluster into a
 * standalone class cannot be verified here — this project has no Android SDK
 * in this environment, only CI can compile `app/` — so extracting it blind
 * would risk a silent behavioural regression with no way to catch it before
 * a device build. Wrapping the existing method reference instead makes
 * "Classic is byte-for-byte identical" true by construction: zero characters
 * of the wrapped logic change.
 *
 * `delegate` is `SessionCoordinator`'s private `classicAnswer(transcript)` —
 * see that class for the actual behaviour, including the one-`if` Modalità
 * Pro gate that must stay reachable ONLY from this engine, never from
 * `ConversationalJarvisEngine`.
 */
class ClassicJarvisEngine(
    private val delegate: suspend (String) -> String,
) : JarvisEngine {
    override suspend fun handle(transcript: String): String = delegate(transcript)
}
