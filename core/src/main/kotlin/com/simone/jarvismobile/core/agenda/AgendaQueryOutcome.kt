package com.simone.jarvismobile.core.agenda

/**
 * § JARVIS Implementation Master Plan — PASSAGGIO 4 (Agenda Read Path
 * Unification + Structured Agenda Outcomes), finding JARVIS-08. The outcome
 * of an `AgendaRepository.queryResult` call (`app/`) — distinguishes a
 * genuine storage read failure from an honest empty result, so a caller can
 * never conflate "the read succeeded and there is nothing there" with "the
 * read itself did not happen". Mirrors the same EMPTY-vs-FAILURE principle
 * `core/tools/StructuredToolResult.kt` (PASSAGGIO 1) already established for
 * the wider outcome taxonomy — see [AgendaEvidence] for the mapping between
 * the two.
 */
sealed interface AgendaQueryOutcome {
    /** The read genuinely succeeded — [entries] is the real, already-filtered result, possibly empty. */
    data class Success(val entries: List<AgendaEntry>) : AgendaQueryOutcome

    /** The read itself failed (e.g. local storage I/O) — [reasonCode] is technical, never personal data. */
    data class Failure(val reasonCode: String) : AgendaQueryOutcome
}
